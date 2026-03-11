package com.example.demo.controller;

import com.example.demo.entity.MistakeTag;
import com.example.demo.entity.Setup;
import com.example.demo.entity.Trade;
import com.example.demo.entity.User;
import com.example.demo.repository.MistakeTagRepository;
import com.example.demo.repository.SetupRepository;
import com.example.demo.repository.TradeMistakeTagRepository;
import com.example.demo.repository.TradeRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.TradeImageService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final TradeRepository tradeRepository;
    private final SetupRepository setupRepository;
    private final MistakeTagRepository mistakeTagRepository;
    private final TradeMistakeTagRepository tradeMistakeTagRepository;
    private final UserService userService;
    private final TradeImageService tradeImageService;

    public AdminController(
            UserRepository userRepository,
            TradeRepository tradeRepository,
            SetupRepository setupRepository,
            MistakeTagRepository mistakeTagRepository,
            TradeMistakeTagRepository tradeMistakeTagRepository,
            UserService userService,
            TradeImageService tradeImageService
    ) {
        this.userRepository = userRepository;
        this.tradeRepository = tradeRepository;
        this.setupRepository = setupRepository;
        this.mistakeTagRepository = mistakeTagRepository;
        this.tradeMistakeTagRepository = tradeMistakeTagRepository;
        this.userService = userService;
        this.tradeImageService = tradeImageService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String dashboard(Model model, HttpSession session) {
        User admin = userService.getCurrentUser(session);

        if (admin == null) {
            return "redirect:/login";
        }

        if (!userService.isAdmin(admin)) {
            return "redirect:/trades";
        }

        List<User> users = userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Trade> trades = tradeRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));

        LocalDate today = LocalDate.now();
        LocalDateTime sevenDaysAgo = today.minusDays(6).atStartOfDay();

        long totalUsers = users.size();
        long activeUsers = users.stream().filter(User::isActive).count();
        long adminUsers = users.stream()
                .filter(user -> user.getRole() != null && "ADMIN".equalsIgnoreCase(user.getRole()))
                .count();
        long disabledUsers = users.stream().filter(user -> !user.isActive()).count();
        long inactiveUsers = 0L;
        long newUsersLast7Days = users.stream()
                .map(User::getCreatedAt)
                .filter(createdAt -> createdAt != null && !createdAt.isBefore(sevenDaysAgo))
                .count();

        long totalTrades = trades.size();
        long tradesToday = trades.stream()
                .map(this::resolveTradeCreatedAt)
                .filter(createdAt -> createdAt != null && createdAt.toLocalDate().isEqual(today))
                .count();
        double averageTradesPerUser = totalUsers == 0 ? 0.0 : (double) totalTrades / totalUsers;

        long activeNonAdminUsers = users.stream()
                .filter(User::isActive)
                .filter(user -> user.getRole() == null || !"ADMIN".equalsIgnoreCase(user.getRole()))
                .count();
        long trialUsers = users.stream()
                .filter(User::isActive)
                .filter(user -> user.getRole() == null || !"ADMIN".equalsIgnoreCase(user.getRole()))
                .filter(user -> user.getCreatedAt() != null && !user.getCreatedAt().isBefore(sevenDaysAgo))
                .count();
        long freeUsers = Math.max(0, activeNonAdminUsers - trialUsers);

        List<DailyCountPoint> userGrowthSeries = buildDailySeries(
                users.stream()
                        .map(User::getCreatedAt)
                        .toList()
        );
        List<DailyCountPoint> tradeVolumeSeries = buildDailySeries(
                trades.stream()
                        .map(this::resolveTradeCreatedAt)
                        .toList()
        );

        List<DistributionSlice> userStatusDistribution = List.of(
                new DistributionSlice("Active users", Math.max(0, activeUsers - adminUsers)),
                new DistributionSlice("Inactive users", inactiveUsers),
                new DistributionSlice("Admin users", adminUsers),
                new DistributionSlice("Disabled users", disabledUsers)
        );
        List<DistributionSlice> planDistribution = List.of(
                new DistributionSlice("Free plan", freeUsers),
                new DistributionSlice("Pro plan", adminUsers),
                new DistributionSlice("Trial users", trialUsers)
        );

        Map<String, String> setupNamesById = setupRepository.findAll().stream()
                .collect(Collectors.toMap(Setup::getId, Setup::getName, (left, right) -> left));
        Map<String, String> mistakeNamesById = mistakeTagRepository.findAll().stream()
                .collect(Collectors.toMap(MistakeTag::getId, MistakeTag::getName, (left, right) -> left));

        List<BehaviorStat> topSymbols = topCounts(
                trades.stream()
                        .map(Trade::getSymbol)
                        .map(this::normalizeLabel)
                        .toList(),
                4
        );
        List<BehaviorStat> topSetups = tradeRepository.summarizeBySetupAllUsers().stream()
                .map(row -> new BehaviorStat(
                        normalizeLabel(setupNamesById.get(row.getSetupId())),
                        row.getTradeCount()
                ))
                .sorted(Comparator.comparingLong(BehaviorStat::count).reversed()
                        .thenComparing(BehaviorStat::label))
                .limit(4)
                .toList();
        List<BehaviorStat> topMistakes = tradeMistakeTagRepository.countUsageByMistakeTag().stream()
                .map(row -> new BehaviorStat(
                        normalizeLabel(mistakeNamesById.get(row.getMistakeTagId())),
                        row.getUsageCount()
                ))
                .sorted(Comparator.comparingLong(BehaviorStat::count).reversed()
                        .thenComparing(BehaviorStat::label))
                .limit(4)
                .toList();

        List<User> recentRegistrations = users.stream().limit(8).toList();
        List<Trade> recentTrades = trades.stream().limit(8).toList();

        List<SystemHealthMetric> healthMetrics = List.of(
                new SystemHealthMetric("Failed logins (24h)", "0", "No failed-login tracking is stored yet.", "success", "bi-shield-lock"),
                new SystemHealthMetric("Disabled accounts", String.valueOf(disabledUsers), "Accounts currently blocked from access.", "warning", "bi-person-slash"),
                new SystemHealthMetric("Server status", "Operational", "Application responded normally on March 11, 2026.", "success", "bi-hdd-network"),
                new SystemHealthMetric("API errors", "0", "No API error telemetry is persisted yet.", "success", "bi-plug")
        );

        model.addAttribute("currentUser", admin);
        model.addAttribute("totalUsers", totalUsers);
        model.addAttribute("activeUsers", activeUsers);
        model.addAttribute("inactiveUsers", inactiveUsers);
        model.addAttribute("adminUsers", adminUsers);
        model.addAttribute("disabledUsers", disabledUsers);
        model.addAttribute("newUsersLast7Days", newUsersLast7Days);
        model.addAttribute("totalTrades", totalTrades);
        model.addAttribute("tradesToday", tradesToday);
        model.addAttribute("averageTradesPerUser", round2(averageTradesPerUser));
        model.addAttribute("userGrowthSeries", userGrowthSeries);
        model.addAttribute("tradeVolumeSeries", tradeVolumeSeries);
        model.addAttribute("userStatusDistribution", userStatusDistribution);
        model.addAttribute("planDistribution", planDistribution);
        model.addAttribute("topSymbols", topSymbols);
        model.addAttribute("topSetups", topSetups);
        model.addAttribute("topMistakes", topMistakes);
        model.addAttribute("recentRegistrations", recentRegistrations);
        model.addAttribute("recentTrades", recentTrades);
        model.addAttribute("healthMetrics", healthMetrics);

        return "admin";
    }

    @GetMapping("/users")
    @Transactional(readOnly = true)
    public String users(Model model, HttpSession session) {
        User admin = userService.getCurrentUser(session);

        if (admin == null) {
            return "redirect:/login";
        }

        if (!userService.isAdmin(admin)) {
            return "redirect:/trades";
        }

        List<User> users = userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Trade> trades = tradeRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime trialCutoff = now.minusDays(7);
        LocalDateTime recentActivityCutoff = now.minusDays(30);

        Map<String, Long> tradesByUserId = trades.stream()
                .filter(trade -> trade.getUser() != null && trade.getUser().getId() != null)
                .collect(Collectors.groupingBy(trade -> trade.getUser().getId(), Collectors.counting()));

        Map<String, LocalDateTime> lastActivityByUserId = trades.stream()
                .filter(trade -> trade.getUser() != null && trade.getUser().getId() != null)
                .collect(Collectors.toMap(
                        trade -> trade.getUser().getId(),
                        this::resolveTradeCreatedAt,
                        (left, right) -> {
                            if (left == null) {
                                return right;
                            }
                            if (right == null) {
                                return left;
                            }
                            return left.isAfter(right) ? left : right;
                        }
                ));

        List<AdminUserRow> userRows = users.stream()
                .map(user -> {
                    long tradeCount = tradesByUserId.getOrDefault(user.getId(), 0L);
                    LocalDateTime lastActiveAt = lastActivityByUserId.get(user.getId());
                    String plan = resolveUserPlan(user, trialCutoff);
                    String status = resolveUserStatus(user, tradeCount, lastActiveAt, recentActivityCutoff);
                    return new AdminUserRow(
                            user.getId(),
                            user.getUsername(),
                            user.getEmail(),
                            normalizeLabel(user.getRole()).toUpperCase(Locale.ENGLISH),
                            isAdminUser(user) ? "role-admin" : "role-user",
                            plan,
                            "plan-" + plan.toLowerCase(Locale.ENGLISH),
                            status,
                            "status-" + status.toLowerCase(Locale.ENGLISH),
                            tradeCount,
                            formatAdminDate(lastActiveAt),
                            formatAdminDate(user.getCreatedAt()),
                            user.isActive()
                    );
                })
                .toList();

        long totalUsers = userRows.size();
        long activeUsers = userRows.stream().filter(row -> "Active".equals(row.status())).count();
        long inactiveUsers = userRows.stream().filter(row -> "Inactive".equals(row.status())).count();
        long adminAccounts = userRows.stream().filter(row -> "ADMIN".equals(row.role())).count();
        long flaggedUsers = userRows.stream().filter(row -> "Flagged".equals(row.status())).count();

        model.addAttribute("currentUser", admin);
        model.addAttribute("totalUsers", totalUsers);
        model.addAttribute("activeUsers", activeUsers);
        model.addAttribute("inactiveUsers", inactiveUsers);
        model.addAttribute("adminAccounts", adminAccounts);
        model.addAttribute("flaggedUsers", flaggedUsers);
        model.addAttribute("displayedUsers", userRows.size());
        model.addAttribute("userRows", userRows);

        return "adminUsers";
    }

    @GetMapping("/users/{id}")
    @Transactional(readOnly = true)
    public String userDetail(@PathVariable String id, Model model, HttpSession session) {
        User admin = userService.getCurrentUser(session);

        if (admin == null) {
            return "redirect:/login";
        }

        if (!userService.isAdmin(admin)) {
            return "redirect:/trades";
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));

        List<Trade> trades = tradeRepository.findByUserIdOrderByEntryTimeDesc(user.getId());
        List<Setup> setups = setupRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        List<TradeMistakeTagRepository.MistakeUsageRow> mistakeUsageRows =
                tradeMistakeTagRepository.countUsageByMistakeTagForUser(user.getId());
        List<TradeMistakeTagRepository.RecentMistakeRow> recentMistakes =
                tradeMistakeTagRepository.findRecentMistakesForUser(user.getId());

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime trialCutoff = now.minusDays(7);
        LocalDateTime recentActivityCutoff = now.minusDays(30);
        LocalDateTime lastTradeAt = trades.stream()
                .map(this::resolveTradeCreatedAt)
                .filter(value -> value != null)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        long totalTrades = trades.size();
        long winningTrades = trades.stream()
                .filter(trade -> trade.getResult() != null && "WIN".equalsIgnoreCase(trade.getResult()))
                .count();
        double winRate = totalTrades == 0 ? 0.0 : (winningTrades * 100.0) / totalTrades;
        double averageR = totalTrades == 0 ? 0.0 : trades.stream().mapToDouble(Trade::getRMultiple).average().orElse(0.0);
        double totalPnl = trades.stream().mapToDouble(Trade::getPnl).sum();
        long mistakesLogged = mistakeUsageRows.stream().mapToLong(TradeMistakeTagRepository.MistakeUsageRow::getUsageCount).sum();

        Map<String, String> mistakeNamesById = mistakeTagRepository.findAll().stream()
                .collect(Collectors.toMap(MistakeTag::getId, MistakeTag::getName, (left, right) -> left));

        String profilePlan = resolveUserPlan(user, trialCutoff);
        String profileStatus = resolveUserStatus(user, totalTrades, lastTradeAt, recentActivityCutoff);

        BehaviorStat mostTradedSymbol = topCounts(
                trades.stream().map(Trade::getSymbol).map(this::normalizeLabel).toList(),
                1
        ).stream().findFirst().orElse(new BehaviorStat("N/A", 0));

        BehaviorStat mostUsedSetup = topCounts(
                trades.stream().map(Trade::getSetupName).map(this::normalizeLabel).toList(),
                1
        ).stream().findFirst().orElse(new BehaviorStat("N/A", 0));

        BehaviorStat preferredSession = topCounts(
                trades.stream().map(Trade::getSession).map(this::normalizeLabel).toList(),
                1
        ).stream().findFirst().orElse(new BehaviorStat("N/A", 0));

        BehaviorStat mostCommonMistake = mistakeUsageRows.stream()
                .map(row -> new BehaviorStat(normalizeLabel(mistakeNamesById.get(row.getMistakeTagId())), row.getUsageCount()))
                .sorted(Comparator.comparingLong(BehaviorStat::count).reversed().thenComparing(BehaviorStat::label))
                .findFirst()
                .orElse(new BehaviorStat("N/A", 0));

        List<UserTradeRow> recentTradeRows = trades.stream()
                .limit(6)
                .map(trade -> new UserTradeRow(
                        normalizeLabel(trade.getSymbol()),
                        normalizeLabel(trade.getDirection()).toUpperCase(Locale.ENGLISH),
                        normalizeLabel(trade.getResult()),
                        formatCurrency(trade.getPnl()),
                        formatSignedR(trade.getRMultiple()),
                        formatDateTime(trade.getEntryTime())
                ))
                .toList();

        List<TimelineEvent> timeline = new ArrayList<>();
        if (user.getCreatedAt() != null) {
            timeline.add(new TimelineEvent("Account created", "User account was registered on the platform.", formatDateTime(user.getCreatedAt()), user.getCreatedAt(), "bi-person-plus", "primary"));
        }
        trades.stream()
                .map(this::resolveTradeCreatedAt)
                .filter(value -> value != null)
                .min(LocalDateTime::compareTo)
                .ifPresent(value -> timeline.add(new TimelineEvent("First trade logged", "Initial trading activity recorded in the journal.", formatDateTime(value), value, "bi-graph-up-arrow", "success")));
        setups.stream()
                .map(Setup::getCreatedAt)
                .filter(value -> value != null)
                .min(LocalDateTime::compareTo)
                .ifPresent(value -> timeline.add(new TimelineEvent("Setup created", "User created their first saved setup template.", formatDateTime(value), value, "bi-sliders2", "warning")));
        recentMistakes.stream()
                .findFirst()
                .ifPresent(event -> timeline.add(new TimelineEvent(
                        "Mistake tagged",
                        normalizeLabel(event.getMistakeName()) + " was tagged on " + normalizeLabel(event.getSymbol()) + ".",
                        formatDateTime(event.getEntryTime()),
                        event.getEntryTime(),
                        "bi-exclamation-triangle",
                        "danger"
                )));
        if (lastTradeAt != null) {
            timeline.add(new TimelineEvent("Last login", "Most recent activity is inferred from the latest trade timestamp.", formatDateTime(lastTradeAt), lastTradeAt, "bi-clock-history", "slate"));
        }
        timeline.sort(Comparator.comparing(TimelineEvent::eventAt, Comparator.nullsLast(LocalDateTime::compareTo)).reversed());

        List<ModerationItem> moderationItems = List.of(
                new ModerationItem("Failed login attempts", "0", "No failed-login telemetry is persisted yet.", "success", "bi-shield-lock"),
                new ModerationItem("Account flags", "1".equals(String.valueOf("Flagged".equals(profileStatus) ? 1 : 0)) ? "1 active flag" : "0 active flags", flaggedReason(profileStatus, totalTrades), "danger", "bi-flag"),
                new ModerationItem("Role change history", "Not tracked", "Role audit events are not persisted in the current schema.", "warning", "bi-arrow-left-right"),
                new ModerationItem("Status change history", "Not tracked", "Account status history is inferred from current state only.", "slate", "bi-clock-history")
        );

        String adminNotes = buildAdminNotes(user, profileStatus, totalTrades, mistakesLogged, lastTradeAt);

        model.addAttribute("currentUser", admin);
        model.addAttribute("profileUser", user);
        model.addAttribute("profileRole", normalizeLabel(user.getRole()).toUpperCase(Locale.ENGLISH));
        model.addAttribute("profileRoleCss", isAdminUser(user) ? "role-admin" : "role-user");
        model.addAttribute("profilePlan", profilePlan);
        model.addAttribute("profilePlanCss", "plan-" + profilePlan.toLowerCase(Locale.ENGLISH));
        model.addAttribute("profileStatus", profileStatus);
        model.addAttribute("profileStatusCss", "status-" + profileStatus.toLowerCase(Locale.ENGLISH));
        model.addAttribute("createdAtLabel", formatDateTime(user.getCreatedAt()));
        model.addAttribute("lastActiveLabel", formatDateTime(lastTradeAt));
        model.addAttribute("avatarInitial", user.getUsername() != null && !user.getUsername().isBlank()
                ? user.getUsername().substring(0, 1).toUpperCase(Locale.ENGLISH)
                : "U");

        model.addAttribute("totalTrades", totalTrades);
        model.addAttribute("winRate", round2(winRate));
        model.addAttribute("averageR", round2(averageR));
        model.addAttribute("totalPnl", formatCurrency(totalPnl));
        model.addAttribute("lastTradeDate", formatDateTime(lastTradeAt));
        model.addAttribute("mistakesLogged", mistakesLogged);

        model.addAttribute("recentTradeRows", recentTradeRows);
        model.addAttribute("mostTradedSymbol", mostTradedSymbol);
        model.addAttribute("mostUsedSetup", mostUsedSetup);
        model.addAttribute("mostCommonMistake", mostCommonMistake);
        model.addAttribute("preferredSession", preferredSession);
        model.addAttribute("timeline", timeline);
        model.addAttribute("adminNotes", adminNotes);
        model.addAttribute("moderationItems", moderationItems);

        return "adminUserDetail";
    }

    @PostMapping("/users/{id}/toggle-active")
    public String toggleUserActive(@PathVariable String id, HttpSession session) {
        User admin = userService.getCurrentUser(session);

        if (admin == null) {
            return "redirect:/login";
        }

        if (!userService.isAdmin(admin)) {
            return "redirect:/trades";
        }

        User target = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));

        if (admin.getId().equals(target.getId())) {
            throw new IllegalArgumentException("You cannot disable yourself");
        }

        target.setActive(!target.isActive());
        userRepository.save(target);

        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/delete")
    public String deleteUser(@PathVariable String id, HttpSession session) {
        User admin = userService.getCurrentUser(session);

        if (admin == null) {
            return "redirect:/login";
        }

        if (!userService.isAdmin(admin)) {
            return "redirect:/trades";
        }

        User target = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));

        if (admin.getId().equals(target.getId())) {
            throw new IllegalArgumentException("You cannot delete yourself");
        }

        List<Trade> userTrades = tradeRepository.findByUserIdOrderByEntryTimeDesc(target.getId());
        for (Trade trade : userTrades) {
            tradeImageService.deleteByTradeId(trade.getId());
        }
        tradeRepository.deleteAll(userTrades);

        userRepository.delete(target);

        return "redirect:/admin/users";
    }

    @PostMapping("/trades/{id}/delete")
    public String deleteTrade(@PathVariable String id, HttpSession session) {
        User admin = userService.getCurrentUser(session);

        if (admin == null) {
            return "redirect:/login";
        }

        if (!userService.isAdmin(admin)) {
            return "redirect:/trades";
        }

        Trade trade = tradeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Trade not found: " + id));

        tradeImageService.deleteByTradeId(trade.getId());
        tradeRepository.delete(trade);

        return "redirect:/admin";
    }

    private List<DailyCountPoint> buildDailySeries(List<LocalDateTime> timestamps) {
        Map<LocalDate, Long> countsByDate = timestamps.stream()
                .filter(value -> value != null)
                .collect(Collectors.groupingBy(
                        LocalDateTime::toLocalDate,
                        LinkedHashMap::new,
                        Collectors.counting()
                ));

        if (countsByDate.isEmpty()) {
            return List.of();
        }

        LocalDate start = countsByDate.keySet().stream().min(LocalDate::compareTo).orElse(LocalDate.now());
        LocalDate end = countsByDate.keySet().stream().max(LocalDate::compareTo).orElse(start);
        List<DailyCountPoint> points = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            points.add(new DailyCountPoint(
                    day.format(formatter),
                    countsByDate.getOrDefault(day, 0L),
                    day
            ));
        }
        return points;
    }

    private List<BehaviorStat> topCounts(List<String> rawValues, int limit) {
        return rawValues.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .map(entry -> new BehaviorStat(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(BehaviorStat::count).reversed()
                        .thenComparing(BehaviorStat::label))
                .limit(limit)
                .toList();
    }

    private LocalDateTime resolveTradeCreatedAt(Trade trade) {
        if (trade == null) {
            return null;
        }
        if (trade.getCreatedAt() != null) {
            return trade.getCreatedAt();
        }
        if (trade.getEntryTime() != null) {
            return trade.getEntryTime();
        }
        return trade.getTradeDate();
    }

    private String normalizeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "N/A";
        }
        return value.trim();
    }

    private boolean isAdminUser(User user) {
        return user.getRole() != null && "ADMIN".equalsIgnoreCase(user.getRole());
    }

    private String resolveUserPlan(User user, LocalDateTime trialCutoff) {
        if (isAdminUser(user)) {
            return "Pro";
        }
        if (user.getCreatedAt() != null && !user.getCreatedAt().isBefore(trialCutoff)) {
            return "Trial";
        }
        return "Free";
    }

    private String resolveUserStatus(User user, long tradeCount, LocalDateTime lastActiveAt, LocalDateTime recentActivityCutoff) {
        if (!user.isActive()) {
            return "Suspended";
        }
        if (tradeCount == 0) {
            return "Flagged";
        }
        if (lastActiveAt == null || lastActiveAt.isBefore(recentActivityCutoff)) {
            return "Inactive";
        }
        return "Active";
    }

    private String formatAdminDate(LocalDateTime value) {
        if (value == null) {
            return "-";
        }

        LocalDateTime now = LocalDateTime.now();
        long minutes = ChronoUnit.MINUTES.between(value, now);
        if (minutes < 1) {
            return "Just now";
        }
        if (minutes < 60) {
            return minutes + " min ago";
        }

        long hours = ChronoUnit.HOURS.between(value, now);
        if (hours < 24) {
            return hours + " hour" + (hours == 1 ? "" : "s") + " ago";
        }

        long days = ChronoUnit.DAYS.between(value.toLocalDate(), now.toLocalDate());
        if (days < 7) {
            return days + " day" + (days == 1 ? "" : "s") + " ago";
        }

        return value.format(DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH));
    }

    private String formatDateTime(LocalDateTime value) {
        if (value == null) {
            return "-";
        }
        return value.format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm", Locale.ENGLISH));
    }

    private String formatCurrency(double value) {
        String prefix = value > 0 ? "+$" : (value < 0 ? "-$" : "$");
        return prefix + String.format(Locale.ENGLISH, "%,.2f", Math.abs(value));
    }

    private String formatSignedR(double value) {
        if (value > 0) {
            return "+" + round2(value) + "R";
        }
        if (value < 0) {
            return round2(value) + "R";
        }
        return "0.0R";
    }

    private String flaggedReason(String profileStatus, long totalTrades) {
        if ("Flagged".equals(profileStatus)) {
            return totalTrades == 0
                    ? "Account is flagged because no trades have been logged yet."
                    : "Account is flagged based on low-confidence activity signals.";
        }
        return "No current moderation flags are inferred for this user.";
    }

    private String buildAdminNotes(User user, String profileStatus, long totalTrades, long mistakesLogged, LocalDateTime lastTradeAt) {
        StringBuilder note = new StringBuilder();
        note.append("Admin summary for ").append(normalizeLabel(user.getUsername())).append(". ");
        note.append("Current status is ").append(profileStatus.toLowerCase(Locale.ENGLISH)).append(". ");
        note.append("Total trades logged: ").append(totalTrades).append(". ");
        note.append("Mistakes tagged: ").append(mistakesLogged).append(". ");
        if (lastTradeAt != null) {
            note.append("Most recent activity was ").append(formatAdminDate(lastTradeAt)).append(". ");
        } else {
            note.append("No trade activity has been recorded yet. ");
        }
        note.append("Internal admin notes are not persisted yet in the current data model.");
        return note.toString();
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record DailyCountPoint(String label, long count, LocalDate date) {
    }

    private record DistributionSlice(String label, long count) {
    }

    private record BehaviorStat(String label, long count) {
    }

    private record SystemHealthMetric(String label, String value, String note, String tone, String icon) {
    }

    private record AdminUserRow(
            String id,
            String username,
            String email,
            String role,
            String roleCss,
            String plan,
            String planCss,
            String status,
            String statusCss,
            long tradeCount,
            String lastActiveLabel,
            String createdAtLabel,
            boolean active
    ) {
    }

    private record UserTradeRow(
            String symbol,
            String direction,
            String result,
            String pnl,
            String rMultiple,
            String entryTime
    ) {
    }

    private record TimelineEvent(
            String title,
            String note,
            String timeLabel,
            LocalDateTime eventAt,
            String icon,
            String tone
    ) {
    }

    private record ModerationItem(
            String label,
            String value,
            String note,
            String tone,
            String icon
    ) {
    }
}
