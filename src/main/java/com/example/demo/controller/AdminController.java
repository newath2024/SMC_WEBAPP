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
}
