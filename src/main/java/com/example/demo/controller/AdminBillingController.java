package com.example.demo.controller;

import com.example.demo.entity.*;
import com.example.demo.repository.BillingInvoiceRepository;
import com.example.demo.repository.BillingSubscriptionRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Controller
@RequestMapping("/admin/billing")
public class AdminBillingController {

    private static final double PRO_MONTHLY_PRICE = 29.0;
    private static final DateTimeFormatter DATE_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private final UserRepository userRepository;
    private final UserService userService;
    private final BillingSubscriptionRepository billingSubscriptionRepository;
    private final BillingInvoiceRepository billingInvoiceRepository;

    public AdminBillingController(
            UserRepository userRepository,
            UserService userService,
            BillingSubscriptionRepository billingSubscriptionRepository,
            BillingInvoiceRepository billingInvoiceRepository
    ) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.billingSubscriptionRepository = billingSubscriptionRepository;
        this.billingInvoiceRepository = billingInvoiceRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String billing(
            @RequestParam(name = "range", defaultValue = "30") String range,
            @RequestParam(name = "invoicePage", defaultValue = "1") int invoicePage,
            Model model,
            HttpSession session
    ) {
        User admin = userService.getCurrentUser(session);
        if (admin == null) {
            return "redirect:/login";
        }
        if (!userService.isAdmin(admin)) {
            return "redirect:/trades";
        }

        RangeConfig rangeConfig = resolveRange(range);
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime trialCutoff = now.minusDays(7);

        List<User> allUsers = userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        List<User> customers = allUsers.stream().filter(user -> !isAdminUser(user)).toList();
        ensureBillingData(customers, trialCutoff, today);

        Set<String> customerIds = customers.stream().map(User::getId).collect(Collectors.toSet());
        List<BillingSubscription> allSubscriptions = billingSubscriptionRepository.findAllByOrderByUpdatedAtDesc().stream()
                .filter(sub -> sub.getUser() != null && customerIds.contains(sub.getUser().getId()))
                .toList();

        LocalDateTime scopeCutoff = rangeConfig.days() == null ? null : now.minusDays(rangeConfig.days());
        List<BillingSubscription> scopedSubscriptions = scopeCutoff == null
                ? allSubscriptions
                : allSubscriptions.stream()
                .filter(sub -> sub.getUpdatedAt() != null && !sub.getUpdatedAt().isBefore(scopeCutoff))
                .toList();

        long totalNonAdminUsers = scopedSubscriptions.size();
        long proUsers = scopedSubscriptions.stream().filter(sub -> sub.getPlan() == BillingPlan.PRO).count();
        long activePaidProUsers = scopedSubscriptions.stream()
                .filter(sub -> sub.getPlan() == BillingPlan.PRO)
                .filter(sub -> sub.getStatus() == SubscriptionStatus.ACTIVE)
                .filter(sub -> sub.getPaymentStatus() == BillingPaymentStatus.PAID)
                .count();
        long trialUsers = scopedSubscriptions.stream()
                .filter(sub -> sub.getPlan() == BillingPlan.TRIAL)
                .count();
        long failedPayments = scopedSubscriptions.stream()
                .filter(sub -> sub.getPaymentStatus() == BillingPaymentStatus.FAILED)
                .count();
        long churnedSubscriptions = scopedSubscriptions.stream()
                .filter(sub -> sub.getStatus() == SubscriptionStatus.CANCELED)
                .count();

        double mrr = activePaidProUsers * PRO_MONTHLY_PRICE;
        double arpu = activePaidProUsers == 0 ? 0.0 : mrr / activePaidProUsers;
        double conversionRate = totalNonAdminUsers == 0 ? 0.0 : (proUsers * 100.0) / totalNonAdminUsers;

        List<TrendPoint> revenueTrend = buildRevenueTrend(allSubscriptions, today, rangeConfig);

        long freeUsers = scopedSubscriptions.stream().filter(sub -> sub.getPlan() == BillingPlan.FREE).count();
        List<ChartSlice> planMix = List.of(
                new ChartSlice("Pro", proUsers),
                new ChartSlice("Trial", trialUsers),
                new ChartSlice("Free", freeUsers)
        );

        List<ChartSlice> paymentStatus = List.of(
                new ChartSlice("Paid", scopedSubscriptions.stream().filter(sub -> sub.getPaymentStatus() == BillingPaymentStatus.PAID).count()),
                new ChartSlice("Pending", scopedSubscriptions.stream().filter(sub -> sub.getPaymentStatus() == BillingPaymentStatus.PENDING).count()),
                new ChartSlice("Failed", scopedSubscriptions.stream().filter(sub -> sub.getPaymentStatus() == BillingPaymentStatus.FAILED).count()),
                new ChartSlice("Refunded", scopedSubscriptions.stream().filter(sub -> sub.getPaymentStatus() == BillingPaymentStatus.REFUNDED).count())
        );

        List<SubscriptionRow> subscriptionRows = scopedSubscriptions.stream()
                .sorted(Comparator.comparing(BillingSubscription::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(20)
                .map(this::toSubscriptionRow)
                .toList();

        List<BillingInvoice> allInvoices = billingInvoiceRepository.findTop200ByOrderByInvoiceDateDesc().stream()
                .filter(inv -> inv.getUser() != null && customerIds.contains(inv.getUser().getId()))
                .toList();

        List<BillingInvoice> scopedInvoices = scopeCutoff == null
                ? allInvoices
                : allInvoices.stream()
                .filter(inv -> !inv.getInvoiceDate().isBefore(scopeCutoff.toLocalDate()))
                .toList();

        int invoicePageSize = 10;
        int totalInvoicePages = Math.max(1, (int) Math.ceil(scopedInvoices.size() / (double) invoicePageSize));
        int safeInvoicePage = Math.max(1, Math.min(invoicePage, totalInvoicePages));
        int invoiceFromIndex = (safeInvoicePage - 1) * invoicePageSize;
        int invoiceToIndex = Math.min(invoiceFromIndex + invoicePageSize, scopedInvoices.size());

        List<InvoiceRow> invoiceRows = scopedInvoices.subList(invoiceFromIndex, invoiceToIndex).stream()
                .map(this::toInvoiceRow)
                .toList();

        List<BillingAlert> failedPaymentAlerts = subscriptionRows.stream()
                .filter(row -> "Failed".equals(row.paymentStatus()))
                .limit(6)
                .map(row -> new BillingAlert(
                        row.userId(),
                        row.userName(),
                        "Payment retry needed",
                        "Last charge of " + row.amount() + " failed for " + row.userName() + ".",
                        "Critical"
                ))
                .toList();

        List<BillingAlert> upcomingExpirationAlerts = scopedSubscriptions.stream()
                .filter(sub -> sub.getPlan() == BillingPlan.TRIAL)
                .filter(sub -> sub.getNextBillingDate() != null)
                .map(sub -> new TrialExpiration(sub, Math.max(0, ChronoUnit.DAYS.between(today, sub.getNextBillingDate()))))
                .filter(item -> item.daysLeft() <= 3)
                .sorted(Comparator.comparingLong(TrialExpiration::daysLeft))
                .limit(6)
                .map(item -> new BillingAlert(
                        item.subscription().getUser().getId(),
                        item.subscription().getUser().getUsername(),
                        "Upcoming expiration",
                        item.subscription().getUser().getUsername() + " trial expires in " + item.daysLeft() + " day" + (item.daysLeft() == 1 ? "" : "s") + ".",
                        "Warning"
                ))
                .toList();

        LocalDateTime planChangeCutoff = scopeCutoff == null ? now.minusDays(3650) : scopeCutoff;
        List<BillingAlert> recentPlanChangeAlerts = allSubscriptions.stream()
                .filter(sub -> sub.getUpdatedAt() != null && !sub.getUpdatedAt().isBefore(planChangeCutoff))
                .sorted(Comparator.comparing(BillingSubscription::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(6)
                .map(sub -> {
                    boolean upgraded = sub.getPlan() == BillingPlan.PRO && sub.getStatus() == SubscriptionStatus.ACTIVE;
                    String title = upgraded ? "Recent upgrade" : "Recent downgrade";
                    String note = upgraded
                            ? sub.getUser().getUsername() + " moved to Pro in selected range."
                            : sub.getUser().getUsername() + " changed to lower tier in selected range.";
                    return new BillingAlert(sub.getUser().getId(), sub.getUser().getUsername(), title, note, upgraded ? "Info" : "Warning");
                })
                .toList();

        model.addAttribute("currentUser", admin);
        model.addAttribute("selectedRange", rangeConfig.key());
        model.addAttribute("selectedRangeLabel", rangeConfig.label());

        model.addAttribute("monthlyRevenue", formatCurrency(mrr));
        model.addAttribute("mrr", formatCurrency(mrr));
        model.addAttribute("arpu", formatCurrency(arpu));
        model.addAttribute("activeProUsers", activePaidProUsers);
        model.addAttribute("trialUsers", trialUsers);
        model.addAttribute("conversionRate", round2(conversionRate));
        model.addAttribute("conversionFormula", "Conversion Rate = Pro Users / Total Non-Admin Users");
        model.addAttribute("conversionInputSummary", proUsers + " / " + totalNonAdminUsers);
        model.addAttribute("failedPayments", failedPayments);
        model.addAttribute("churnedSubscriptions", churnedSubscriptions);

        model.addAttribute("revenueTrendLabels", revenueTrend.stream().map(TrendPoint::label).toList());
        model.addAttribute("revenueTrendValues", revenueTrend.stream().map(TrendPoint::value).toList());
        model.addAttribute("planMixLabels", planMix.stream().map(ChartSlice::label).toList());
        model.addAttribute("planMixValues", planMix.stream().map(ChartSlice::value).toList());
        model.addAttribute("paymentStatusLabels", paymentStatus.stream().map(ChartSlice::label).toList());
        model.addAttribute("paymentStatusValues", paymentStatus.stream().map(ChartSlice::value).toList());

        model.addAttribute("subscriptionRows", subscriptionRows);
        model.addAttribute("invoiceRows", invoiceRows);
        model.addAttribute("invoicePage", safeInvoicePage);
        model.addAttribute("invoiceTotalPages", totalInvoicePages);
        model.addAttribute("invoiceHasPrev", safeInvoicePage > 1);
        model.addAttribute("invoiceHasNext", safeInvoicePage < totalInvoicePages);
        model.addAttribute("invoicePageNumbers", IntStream.rangeClosed(1, totalInvoicePages).boxed().toList());
        model.addAttribute("failedPaymentAlerts", failedPaymentAlerts);
        model.addAttribute("upcomingExpirationAlerts", upcomingExpirationAlerts);
        model.addAttribute("recentPlanChangeAlerts", recentPlanChangeAlerts);

        return "adminBilling";
    }

    @PostMapping("/subscriptions/{userId}/cancel")
    @Transactional
    public String cancelSubscription(@PathVariable String userId, HttpSession session) {
        requireAdmin(session);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        BillingSubscription sub = billingSubscriptionRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultSubscription(target, LocalDate.now(), LocalDateTime.now().minusDays(7)));

        sub.setPlan(BillingPlan.FREE);
        sub.setStatus(SubscriptionStatus.CANCELED);
        sub.setBillingCycle(BillingCycle.NONE);
        sub.setAmount(0.0);
        sub.setPaymentStatus(BillingPaymentStatus.PENDING);
        sub.setNextBillingDate(null);
        billingSubscriptionRepository.save(sub);

        target.setPlanType(PlanType.STANDARD);
        target.setActive(true);
        userRepository.save(target);

        addInvoice(target, 0.0, BillingPaymentStatus.REFUNDED, "Admin cancellation", LocalDate.now());
        return "redirect:/admin/billing";
    }

    @PostMapping("/subscriptions/{userId}/extend-trial")
    @Transactional
    public String extendTrial(@PathVariable String userId, HttpSession session) {
        requireAdmin(session);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        BillingSubscription sub = billingSubscriptionRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultSubscription(target, LocalDate.now(), LocalDateTime.now().minusDays(7)));

        LocalDate base = sub.getNextBillingDate() != null && sub.getNextBillingDate().isAfter(LocalDate.now())
                ? sub.getNextBillingDate()
                : LocalDate.now();

        sub.setPlan(BillingPlan.TRIAL);
        sub.setStatus(SubscriptionStatus.TRIALING);
        sub.setBillingCycle(BillingCycle.TRIAL);
        sub.setAmount(0.0);
        sub.setPaymentStatus(BillingPaymentStatus.PENDING);
        sub.setNextBillingDate(base.plusDays(7));
        billingSubscriptionRepository.save(sub);

        target.setPlanType(PlanType.STANDARD);
        target.setActive(true);
        userRepository.save(target);

        return "redirect:/admin/billing";
    }

    @PostMapping("/subscriptions/{userId}/retry-payment")
    @Transactional
    public String retryPayment(@PathVariable String userId, HttpSession session) {
        requireAdmin(session);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        BillingSubscription sub = billingSubscriptionRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultSubscription(target, LocalDate.now(), LocalDateTime.now().minusDays(7)));

        sub.setPlan(BillingPlan.PRO);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setBillingCycle(BillingCycle.MONTHLY);
        sub.setAmount(PRO_MONTHLY_PRICE);
        sub.setPaymentStatus(BillingPaymentStatus.PAID);
        sub.setNextBillingDate(LocalDate.now().plusDays(30));
        billingSubscriptionRepository.save(sub);

        target.setPlanType(PlanType.PRO);
        target.setActive(true);
        userRepository.save(target);

        addInvoice(target, PRO_MONTHLY_PRICE, BillingPaymentStatus.PAID, "Card retry", LocalDate.now());
        return "redirect:/admin/billing";
    }

    @PostMapping("/subscriptions/{userId}/change-plan")
    @Transactional
    public String changePlan(@PathVariable String userId, HttpSession session) {
        requireAdmin(session);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        BillingSubscription sub = billingSubscriptionRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultSubscription(target, LocalDate.now(), LocalDateTime.now().minusDays(7)));

        if (sub.getPlan() == BillingPlan.PRO) {
            sub.setPlan(BillingPlan.FREE);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setBillingCycle(BillingCycle.NONE);
            sub.setAmount(0.0);
            sub.setPaymentStatus(BillingPaymentStatus.PENDING);
            sub.setNextBillingDate(null);

            target.setPlanType(PlanType.STANDARD);
            target.setActive(true);
        } else {
            sub.setPlan(BillingPlan.PRO);
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setBillingCycle(BillingCycle.MONTHLY);
            sub.setAmount(PRO_MONTHLY_PRICE);
            sub.setPaymentStatus(BillingPaymentStatus.PAID);
            sub.setNextBillingDate(LocalDate.now().plusDays(30));

            target.setPlanType(PlanType.PRO);
            target.setActive(true);
            addInvoice(target, PRO_MONTHLY_PRICE, BillingPaymentStatus.PAID, "Plan change", LocalDate.now());
        }

        billingSubscriptionRepository.save(sub);
        userRepository.save(target);

        return "redirect:/admin/billing";
    }

    private void ensureBillingData(List<User> users, LocalDateTime trialCutoff, LocalDate today) {
        for (User user : users) {
            billingSubscriptionRepository.findByUserId(user.getId())
                    .orElseGet(() -> billingSubscriptionRepository.save(createDefaultSubscription(user, today, trialCutoff)));
        }

        if (billingInvoiceRepository.count() > 0) {
            return;
        }

        List<BillingSubscription> subscriptions = billingSubscriptionRepository.findAllByOrderByUpdatedAtDesc();
        for (BillingSubscription sub : subscriptions) {
            if (sub.getPlan() == BillingPlan.PRO || sub.getPlan() == BillingPlan.TRIAL) {
                addInvoice(sub.getUser(), sub.getAmount(), sub.getPaymentStatus(), sub.getPlan() == BillingPlan.PRO ? "Visa" : "No charge", today.minusDays(Math.floorMod(sub.getUser().getId().hashCode(), 20)));
            }
        }
    }

    private BillingSubscription createDefaultSubscription(User user, LocalDate today, LocalDateTime trialCutoff) {
        BillingSubscription sub = new BillingSubscription();
        sub.setUser(user);
        sub.setStartDate(user.getCreatedAt() != null ? user.getCreatedAt().toLocalDate() : today);

        if (user.getPlanType() == PlanType.PRO) {
            sub.setPlan(BillingPlan.PRO);
            sub.setStatus(user.isActive() ? SubscriptionStatus.ACTIVE : SubscriptionStatus.PAST_DUE);
            sub.setBillingCycle(BillingCycle.MONTHLY);
            sub.setAmount(PRO_MONTHLY_PRICE);
            sub.setPaymentStatus(user.isActive() ? BillingPaymentStatus.PAID : BillingPaymentStatus.FAILED);
            sub.setNextBillingDate(today.plusDays(1 + Math.floorMod(user.getId().hashCode(), 27)));
            return sub;
        }

        if (user.getCreatedAt() != null && !user.getCreatedAt().isBefore(trialCutoff) && user.isActive()) {
            sub.setPlan(BillingPlan.TRIAL);
            sub.setStatus(SubscriptionStatus.TRIALING);
            sub.setBillingCycle(BillingCycle.TRIAL);
            sub.setAmount(0.0);
            sub.setPaymentStatus(BillingPaymentStatus.PENDING);
            sub.setNextBillingDate(user.getCreatedAt().toLocalDate().plusDays(7));
            return sub;
        }

        sub.setPlan(BillingPlan.FREE);
        sub.setStatus(user.isActive() ? SubscriptionStatus.ACTIVE : SubscriptionStatus.CANCELED);
        sub.setBillingCycle(BillingCycle.NONE);
        sub.setAmount(0.0);
        sub.setPaymentStatus(BillingPaymentStatus.PENDING);
        sub.setNextBillingDate(null);
        return sub;
    }

    private void addInvoice(User user, double amount, BillingPaymentStatus status, String paymentMethod, LocalDate invoiceDate) {
        BillingInvoice invoice = new BillingInvoice();
        invoice.setUser(user);
        invoice.setAmount(amount);
        invoice.setStatus(status);
        invoice.setPaymentMethod(paymentMethod);
        invoice.setInvoiceDate(invoiceDate);
        invoice.setBillingPeriod(YearMonth.from(invoiceDate.minusMonths(1)).format(MONTH_LABEL_FORMAT));
        invoice.setInvoiceNo(buildInvoiceNo(invoiceDate, user.getId()));
        invoice.setDownloadLabel("PDF");
        billingInvoiceRepository.save(invoice);
    }

    private String buildInvoiceNo(LocalDate date, String userId) {
        return "INV-" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + Math.floorMod(Objects.hash(date, userId, System.nanoTime()), 10000);
    }

    private SubscriptionRow toSubscriptionRow(BillingSubscription sub) {
        return new SubscriptionRow(
                sub.getUser().getId(),
                sub.getUser().getUsername(),
                toLabel(sub.getPlan()),
                toLabel(sub.getStatus()),
                cssToken(toLabel(sub.getStatus())),
                sub.getStartDate() != null ? sub.getStartDate().format(DATE_LABEL_FORMAT) : "-",
                toLabel(sub.getBillingCycle()),
                sub.getNextBillingDate() != null ? sub.getNextBillingDate().format(DATE_LABEL_FORMAT) : "-",
                formatCurrency(sub.getAmount()),
                toLabel(sub.getPaymentStatus()),
                cssToken(toLabel(sub.getPaymentStatus()))
        );
    }

    private InvoiceRow toInvoiceRow(BillingInvoice invoice) {
        return new InvoiceRow(
                invoice.getInvoiceNo(),
                invoice.getUser().getUsername(),
                formatCurrency(invoice.getAmount()),
                invoice.getInvoiceDate().format(DATE_LABEL_FORMAT),
                invoice.getBillingPeriod(),
                toLabel(invoice.getStatus()),
                cssToken(toLabel(invoice.getStatus())),
                invoice.getPaymentMethod(),
                invoice.getDownloadLabel()
        );
    }

    private List<TrendPoint> buildRevenueTrend(List<BillingSubscription> subscriptions, LocalDate today, RangeConfig rangeConfig) {
        if (rangeConfig.days() != null) {
            List<TrendPoint> trend = new ArrayList<>();
            LocalDate start = today.minusDays(rangeConfig.days() - 1L);
            for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
                LocalDate pointDate = day;
                long activePaid = subscriptions.stream()
                        .filter(sub -> sub.getPlan() == BillingPlan.PRO)
                        .filter(sub -> sub.getStatus() == SubscriptionStatus.ACTIVE)
                        .filter(sub -> sub.getPaymentStatus() == BillingPaymentStatus.PAID)
                        .filter(sub -> sub.getStartDate() != null && !sub.getStartDate().isAfter(pointDate))
                        .count();
                trend.add(new TrendPoint(day.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)), round2(activePaid * PRO_MONTHLY_PRICE)));
            }
            return trend;
        }

        List<TrendPoint> trend = new ArrayList<>();
        YearMonth thisMonth = YearMonth.from(today);
        for (int i = 11; i >= 0; i--) {
            YearMonth month = thisMonth.minusMonths(i);
            LocalDate monthEnd = month.atEndOfMonth();
            long activePaid = subscriptions.stream()
                    .filter(sub -> sub.getPlan() == BillingPlan.PRO)
                    .filter(sub -> sub.getStatus() == SubscriptionStatus.ACTIVE)
                    .filter(sub -> sub.getPaymentStatus() == BillingPaymentStatus.PAID)
                    .filter(sub -> sub.getStartDate() != null && !sub.getStartDate().isAfter(monthEnd))
                    .count();
            trend.add(new TrendPoint(month.format(MONTH_LABEL_FORMAT), round2(activePaid * PRO_MONTHLY_PRICE)));
        }
        return trend;
    }

    private RangeConfig resolveRange(String range) {
        return switch (range == null ? "30" : range.toUpperCase(Locale.ENGLISH)) {
            case "7" -> new RangeConfig("7", "Last 7 days", 7);
            case "90" -> new RangeConfig("90", "Last 90 days", 90);
            case "ALL" -> new RangeConfig("ALL", "All time", null);
            default -> new RangeConfig("30", "Last 30 days", 30);
        };
    }

    private User requireAdmin(HttpSession session) {
        User admin = userService.getCurrentUser(session);
        if (admin == null || !userService.isAdmin(admin)) {
            throw new IllegalArgumentException("Admin permission required");
        }
        return admin;
    }

    private boolean isAdminUser(User user) {
        return user.getRole() != null && "ADMIN".equalsIgnoreCase(user.getRole());
    }

    private String toLabel(Enum<?> value) {
        if (value == null) {
            return "N/A";
        }
        String[] parts = value.name().toLowerCase(Locale.ENGLISH).split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private String cssToken(String value) {
        return value == null ? "secondary" : value.trim().toLowerCase(Locale.ENGLISH).replace(" ", "-");
    }

    private String formatCurrency(double value) {
        return "$" + String.format(Locale.ENGLISH, "%,.2f", value);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record TrendPoint(String label, double value) {
    }

    private record ChartSlice(String label, long value) {
    }

    private record TrialExpiration(BillingSubscription subscription, long daysLeft) {
    }

    private record RangeConfig(String key, String label, Integer days) {
    }

    private record SubscriptionRow(
            String userId,
            String userName,
            String plan,
            String status,
            String statusCss,
            String startDate,
            String billingCycle,
            String nextBillingDate,
            String amount,
            String paymentStatus,
            String paymentStatusCss
    ) {
    }

    private record InvoiceRow(
            String invoiceId,
            String userName,
            String amount,
            String date,
            String billingPeriod,
            String status,
            String statusCss,
            String paymentMethod,
            String downloadLabel
    ) {
    }

    private record BillingAlert(
            String userId,
            String userName,
            String title,
            String note,
            String severity
    ) {
    }
}
