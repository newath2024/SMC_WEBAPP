package com.tradejournal.controller;

import com.tradejournal.entity.Trade;
import com.tradejournal.entity.TradeMistakeTag;
import com.tradejournal.entity.User;
import com.tradejournal.repository.TradeMistakeTagRepository;
import com.tradejournal.repository.TradeRepository;
import com.tradejournal.repository.UserRepository;
import com.tradejournal.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/analytics")
public class AdminAnalyticsController {

    private static final DateTimeFormatter DAY_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter COHORT_LABEL_FORMAT = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    private final UserRepository userRepository;
    private final TradeRepository tradeRepository;
    private final TradeMistakeTagRepository tradeMistakeTagRepository;
    private final UserService userService;

    public AdminAnalyticsController(
            UserRepository userRepository,
            TradeRepository tradeRepository,
            TradeMistakeTagRepository tradeMistakeTagRepository,
            UserService userService
    ) {
        this.userRepository = userRepository;
        this.tradeRepository = tradeRepository;
        this.tradeMistakeTagRepository = tradeMistakeTagRepository;
        this.userService = userService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String analytics(Model model, HttpSession session) {
        User admin = userService.getCurrentUser(session);
        if (admin == null) {
            return "redirect:/login";
        }
        if (!userService.isAdmin(admin)) {
            return "redirect:/trades";
        }

        AdminAnalyticsData analyticsData = buildAdminAnalyticsData();
        model.addAttribute("currentUser", admin);
        model.addAttribute("engagementTrend", analyticsData.engagementTrend());
        model.addAttribute("cohortRows", analyticsData.cohortRows());
        model.addAttribute("tradeDistribution", analyticsData.tradeDistribution());
        model.addAttribute("symbolPopularity", analyticsData.symbolPopularity());
        model.addAttribute("setupPopularity", analyticsData.setupPopularity());
        model.addAttribute("mistakeFrequency", analyticsData.mistakeFrequency());
        model.addAttribute("resultDistribution", analyticsData.resultDistribution());
        model.addAttribute("rDistribution", analyticsData.rDistribution());
        model.addAttribute("sessionPerformance", analyticsData.sessionPerformance());
        model.addAttribute("topActiveUsers", analyticsData.topActiveUsers());
        model.addAttribute("inactiveUsers", analyticsData.inactiveUsers());
        model.addAttribute("abnormalUsers", analyticsData.abnormalUsers());
        model.addAttribute("excessiveDailyTradeUsers", analyticsData.excessiveDailyTradeUsers());
        model.addAttribute("suspiciousUsers", analyticsData.suspiciousUsers());
        model.addAttribute("disabledUsers", analyticsData.disabledUsers());
        model.addAttribute("retentionHeaders", List.of("Week 0", "Week 1", "Week 2", "Week 3", "Week 4", "Week 5"));
        model.addAttribute("analyticsSummary", analyticsData.summary());
        return "adminAnalytics";
    }

    private AdminAnalyticsData buildAdminAnalyticsData() {
        List<User> allUsers = userRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));
        Map<String, User> traderUsers = allUsers.stream()
                .filter(user -> !isAdminUser(user))
                .collect(Collectors.toMap(User::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        List<Trade> allTrades = tradeRepository.findAll(Sort.by(Sort.Direction.ASC, "entryTime"));
        List<Trade> trades = allTrades.stream()
                .filter(trade -> trade.getUser() != null)
                .filter(trade -> traderUsers.containsKey(trade.getUser().getId()))
                .toList();

        Map<LocalDate, Set<String>> activeUsersByDay = new LinkedHashMap<>();
        Map<String, Set<LocalDate>> activeDaysByUser = new LinkedHashMap<>();
        Map<String, List<Trade>> tradesByUser = new LinkedHashMap<>();

        for (Trade trade : trades) {
            LocalDate activityDate = resolveActivityDate(trade);
            if (activityDate == null || trade.getUser() == null) {
                continue;
            }
            String userId = trade.getUser().getId();
            activeUsersByDay.computeIfAbsent(activityDate, ignored -> new LinkedHashSet<>()).add(userId);
            activeDaysByUser.computeIfAbsent(userId, ignored -> new LinkedHashSet<>()).add(activityDate);
            tradesByUser.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(trade);
        }

        List<TradeMistakeTag> mistakeLinks = tradeMistakeTagRepository.findAll();
        Map<String, String> tradeOwnerByTradeId = trades.stream()
                .collect(Collectors.toMap(Trade::getId, trade -> trade.getUser().getId(), (left, right) -> left));

        List<EngagementPoint> engagementTrend = buildEngagementTrend(activeUsersByDay);
        List<CohortRow> cohortRows = buildRetentionCohorts(traderUsers.values(), activeDaysByUser);
        List<CountPoint> tradeDistribution = buildTradeDistribution(traderUsers.values(), tradesByUser);
        List<CountPoint> symbolPopularity = topCountPoints(
                trades.stream().map(Trade::getSymbol).map(this::normalizeLabel).toList(),
                6
        );
        List<CountPoint> setupPopularity = topCountPoints(
                trades.stream().map(Trade::getSetupName).map(this::normalizeLabel).toList(),
                6
        );
        List<CountPoint> mistakeFrequency = topCountPoints(
                mistakeLinks.stream()
                        .filter(link -> link.getTrade() != null)
                        .filter(link -> tradeOwnerByTradeId.containsKey(link.getTrade().getId()))
                        .map(link -> normalizeLabel(link.getMistakeTag() != null ? link.getMistakeTag().getName() : null))
                        .toList(),
                6
        );
        List<ResultSlice> resultDistribution = buildResultDistribution(trades);
        List<CountPoint> rDistribution = buildRDistribution(trades);
        List<SessionPerformancePoint> sessionPerformance = buildSessionPerformance(trades);
        List<UserAnalyticsRow> topActiveUsers = buildTopActiveUsers(traderUsers.values(), tradesByUser);
        List<InactiveUserRow> inactiveUsers = buildInactiveUsers(traderUsers.values(), tradesByUser);
        List<MonitoringRow> abnormalUsers = buildAbnormalUsers(traderUsers.values(), tradesByUser);
        List<MonitoringRow> excessiveDailyTradeUsers = buildExcessiveDailyTradeUsers(traderUsers.values(), tradesByUser);
        List<MonitoringRow> suspiciousUsers = buildSuspiciousUsers(traderUsers.values(), tradesByUser, mistakeLinks, tradeOwnerByTradeId);
        List<DisabledUserRow> disabledUsers = buildDisabledUsers(traderUsers.values(), tradesByUser);
        AnalyticsSummary summary = buildSummary(engagementTrend, topActiveUsers, suspiciousUsers, trades.size());

        return new AdminAnalyticsData(
                engagementTrend,
                cohortRows,
                tradeDistribution,
                symbolPopularity,
                setupPopularity,
                mistakeFrequency,
                resultDistribution,
                rDistribution,
                sessionPerformance,
                topActiveUsers,
                inactiveUsers,
                abnormalUsers,
                excessiveDailyTradeUsers,
                suspiciousUsers,
                disabledUsers,
                summary
        );
    }

    private List<EngagementPoint> buildEngagementTrend(Map<LocalDate, Set<String>> activeUsersByDay) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(89);
        List<EngagementPoint> points = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
            long dau = activeUsersByDay.getOrDefault(day, Set.of()).size();
            long wau = unionCount(activeUsersByDay, day.minusDays(6), day);
            long mau = unionCount(activeUsersByDay, day.minusDays(29), day);
            points.add(new EngagementPoint(day.format(DAY_LABEL_FORMAT), dau, wau, mau));
        }
        return points;
    }

    private long unionCount(Map<LocalDate, Set<String>> activeUsersByDay, LocalDate start, LocalDate end) {
        Set<String> users = new LinkedHashSet<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            users.addAll(activeUsersByDay.getOrDefault(day, Set.of()));
        }
        return users.size();
    }

    private List<CohortRow> buildRetentionCohorts(Collection<User> users, Map<String, Set<LocalDate>> activeDaysByUser) {
        Map<LocalDate, List<User>> usersByWeek = users.stream()
                .filter(user -> user.getCreatedAt() != null)
                .collect(Collectors.groupingBy(
                        user -> startOfWeek(user.getCreatedAt().toLocalDate()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<LocalDate> weeks = usersByWeek.keySet().stream()
                .sorted(Comparator.reverseOrder())
                .limit(6)
                .sorted()
                .toList();

        List<CohortRow> rows = new ArrayList<>();
        for (LocalDate weekStart : weeks) {
            List<User> cohortUsers = usersByWeek.getOrDefault(weekStart, List.of());
            List<Integer> values = new ArrayList<>();
            for (int offset = 0; offset < 6; offset++) {
                if (offset == 0) {
                    values.add(100);
                    continue;
                }
                LocalDate targetWeek = weekStart.plusWeeks(offset);
                long retained = cohortUsers.stream()
                        .filter(user -> activeDaysByUser.getOrDefault(user.getId(), Set.of()).stream()
                                .map(this::startOfWeek)
                                .anyMatch(targetWeek::equals))
                        .count();
                values.add(cohortUsers.isEmpty() ? 0 : (int) Math.round((retained * 100.0) / cohortUsers.size()));
            }
            rows.add(new CohortRow(weekStart.format(COHORT_LABEL_FORMAT), cohortUsers.size(), values));
        }
        return rows;
    }

    private List<CountPoint> buildTradeDistribution(Collection<User> users, Map<String, List<Trade>> tradesByUser) {
        long zeroToFive = 0;
        long fiveToTen = 0;
        long tenToTwenty = 0;
        long twentyPlus = 0;

        for (User user : users) {
            int count = tradesByUser.getOrDefault(user.getId(), List.of()).size();
            if (count <= 5) {
                zeroToFive++;
            } else if (count <= 10) {
                fiveToTen++;
            } else if (count <= 20) {
                tenToTwenty++;
            } else {
                twentyPlus++;
            }
        }

        return List.of(
                new CountPoint("0-5 trades", zeroToFive),
                new CountPoint("5-10 trades", fiveToTen),
                new CountPoint("10-20 trades", tenToTwenty),
                new CountPoint("20+ trades", twentyPlus)
        );
    }

    private List<ResultSlice> buildResultDistribution(List<Trade> trades) {
        long wins = trades.stream().filter(trade -> "WIN".equals(normalizeResult(trade.getResult()))).count();
        long losses = trades.stream().filter(trade -> "LOSS".equals(normalizeResult(trade.getResult()))).count();
        long breakeven = Math.max(0, trades.size() - Math.toIntExact(wins) - Math.toIntExact(losses));
        return List.of(
                new ResultSlice("Win trades", wins, "#16a34a"),
                new ResultSlice("Loss trades", losses, "#dc2626"),
                new ResultSlice("Break-even trades", breakeven, "#f59e0b")
        );
    }

    private List<CountPoint> buildRDistribution(List<Trade> trades) {
        long minusTwo = 0;
        long minusOne = 0;
        long zero = 0;
        long one = 0;
        long two = 0;
        long three = 0;

        for (Trade trade : trades) {
            if (!trade.hasKnownRMultiple()) {
                continue;
            }
            double value = trade.getRMultiple();
            if (value <= -2.0) {
                minusTwo++;
            } else if (value < 0.0) {
                minusOne++;
            } else if (Math.abs(value) < 0.0001) {
                zero++;
            } else if (value <= 1.0) {
                one++;
            } else if (value <= 2.0) {
                two++;
            } else {
                three++;
            }
        }

        return List.of(
                new CountPoint("-2R", minusTwo),
                new CountPoint("-1R", minusOne),
                new CountPoint("0", zero),
                new CountPoint("1R", one),
                new CountPoint("2R", two),
                new CountPoint("3R", three)
        );
    }

    private List<SessionPerformancePoint> buildSessionPerformance(List<Trade> trades) {
        return List.of("LONDON", "NEW_YORK", "ASIA").stream()
                .map(session -> {
                    List<Trade> sessionTrades = trades.stream()
                            .filter(trade -> session.equals(normalizeSession(trade.getSession())))
                            .toList();
                    double avgR = sessionTrades.stream()
                            .filter(Trade::hasKnownRMultiple)
                            .mapToDouble(Trade::getRMultiple)
                            .average()
                            .orElse(0.0);
                    return new SessionPerformancePoint(
                            formatSessionLabel(session),
                            round2(avgR),
                            sessionTrades.size(),
                            round2(sessionTrades.stream().mapToDouble(Trade::getPnl).sum())
                    );
                })
                .toList();
    }

    private List<UserAnalyticsRow> buildTopActiveUsers(Collection<User> users, Map<String, List<Trade>> tradesByUser) {
        return users.stream()
                .map(user -> {
                    List<Trade> userTrades = tradesByUser.getOrDefault(user.getId(), List.of());
                    long wins = userTrades.stream().filter(trade -> "WIN".equals(normalizeResult(trade.getResult()))).count();
                    double winRate = userTrades.isEmpty() ? 0.0 : (wins * 100.0) / userTrades.size();
                    double pnl = userTrades.stream().mapToDouble(Trade::getPnl).sum();
                    LocalDateTime lastActive = userTrades.stream()
                            .map(this::resolveTradeCreatedAt)
                            .filter(Objects::nonNull)
                            .max(LocalDateTime::compareTo)
                            .orElse(user.getUpdatedAt() != null ? user.getUpdatedAt() : user.getCreatedAt());
                    return new UserAnalyticsRow(
                            user.getUsername(),
                            userTrades.size(),
                            round2(winRate),
                            round2(pnl),
                            formatDateTime(lastActive),
                            lastActive
                    );
                })
                .sorted(Comparator.comparingLong(UserAnalyticsRow::trades).reversed()
                        .thenComparing(UserAnalyticsRow::lastActiveRaw, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(8)
                .toList();
    }

    private List<InactiveUserRow> buildInactiveUsers(Collection<User> users, Map<String, List<Trade>> tradesByUser) {
        LocalDateTime cutoff = LocalDate.now().minusDays(30).atStartOfDay();
        return users.stream()
                .map(user -> {
                    LocalDateTime lastTrade = tradesByUser.getOrDefault(user.getId(), List.of()).stream()
                            .map(this::resolveTradeCreatedAt)
                            .filter(Objects::nonNull)
                            .max(LocalDateTime::compareTo)
                            .orElse(null);
                    return new InactiveUserRow(
                            user.getUsername(),
                            "Not tracked",
                            formatDateTime(lastTrade),
                            user.isActive() ? "Active" : "Disabled",
                            lastTrade
                    );
                })
                .filter(row -> row.lastTradeRaw() == null || row.lastTradeRaw().isBefore(cutoff))
                .sorted(Comparator.comparing(InactiveUserRow::lastTradeRaw, Comparator.nullsFirst(Comparator.naturalOrder())))
                .limit(8)
                .toList();
    }

    private List<MonitoringRow> buildAbnormalUsers(Collection<User> users, Map<String, List<Trade>> tradesByUser) {
        List<Integer> tradeCounts = users.stream()
                .map(user -> tradesByUser.getOrDefault(user.getId(), List.of()).size())
                .toList();
        double average = tradeCounts.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double variance = tradeCounts.stream()
                .mapToDouble(count -> Math.pow(count - average, 2))
                .average()
                .orElse(0.0);
        double threshold = average + Math.sqrt(variance);

        return users.stream()
                .map(user -> {
                    List<Trade> userTrades = tradesByUser.getOrDefault(user.getId(), List.of());
                    LocalDateTime lastActive = userTrades.stream()
                            .map(this::resolveTradeCreatedAt)
                            .filter(Objects::nonNull)
                            .max(LocalDateTime::compareTo)
                            .orElse(null);
                    return new MonitoringRow(
                            user.getUsername(),
                            "Trade volume outlier",
                            userTrades.size() + " trades",
                            "Above baseline of " + round2(average) + " trades per user",
                            formatDateTime(lastActive)
                    );
                })
                .filter(row -> parseLeadingInt(row.metric()) >= Math.max(8, (int) Math.ceil(threshold)))
                .sorted(Comparator.comparingInt((MonitoringRow row) -> parseLeadingInt(row.metric())).reversed())
                .limit(6)
                .toList();
    }

    private List<MonitoringRow> buildExcessiveDailyTradeUsers(Collection<User> users, Map<String, List<Trade>> tradesByUser) {
        return users.stream()
                .map(user -> {
                    Map<LocalDate, Long> countsByDay = tradesByUser.getOrDefault(user.getId(), List.of()).stream()
                            .map(this::resolveActivityDate)
                            .filter(Objects::nonNull)
                            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
                    Map.Entry<LocalDate, Long> peakDay = countsByDay.entrySet().stream()
                            .max(Map.Entry.comparingByValue())
                            .orElse(null);
                    LocalDateTime lastActive = tradesByUser.getOrDefault(user.getId(), List.of()).stream()
                            .map(this::resolveTradeCreatedAt)
                            .filter(Objects::nonNull)
                            .max(LocalDateTime::compareTo)
                            .orElse(null);
                    return new MonitoringRow(
                            user.getUsername(),
                            "Excessive daily trades",
                            peakDay == null ? "0 trades/day" : peakDay.getValue() + " trades/day",
                            peakDay == null ? "No concentrated day detected" : "Peak on " + peakDay.getKey(),
                            formatDateTime(lastActive)
                    );
                })
                .filter(row -> parseLeadingInt(row.metric()) >= 6)
                .sorted(Comparator.comparingInt((MonitoringRow row) -> parseLeadingInt(row.metric())).reversed())
                .limit(6)
                .toList();
    }

    private List<MonitoringRow> buildSuspiciousUsers(
            Collection<User> users,
            Map<String, List<Trade>> tradesByUser,
            List<TradeMistakeTag> mistakeLinks,
            Map<String, String> tradeOwnerByTradeId
    ) {
        Map<String, Long> mistakesByUser = mistakeLinks.stream()
                .filter(link -> link.getTrade() != null)
                .filter(link -> tradeOwnerByTradeId.containsKey(link.getTrade().getId()))
                .collect(Collectors.groupingBy(link -> tradeOwnerByTradeId.get(link.getTrade().getId()), Collectors.counting()));

        return users.stream()
                .map(user -> {
                    List<Trade> userTrades = tradesByUser.getOrDefault(user.getId(), List.of());
                    long tradeCount = userTrades.size();
                    long lossCount = userTrades.stream().filter(trade -> "LOSS".equals(normalizeResult(trade.getResult()))).count();
                    long mistakeCount = mistakesByUser.getOrDefault(user.getId(), 0L);
                    double lossRate = tradeCount == 0 ? 0.0 : (lossCount * 100.0) / tradeCount;
                    double mistakeRate = tradeCount == 0 ? 0.0 : (mistakeCount * 100.0) / tradeCount;
                    String reason = lossRate >= 80.0 && tradeCount >= 10 ? "High loss concentration" : "High mistake density";
                    String metric = lossRate >= 80.0 && tradeCount >= 10
                            ? round2(lossRate) + "% losses"
                            : round2(mistakeRate) + "% mistake-tag rate";
                    LocalDateTime lastActive = userTrades.stream()
                            .map(this::resolveTradeCreatedAt)
                            .filter(Objects::nonNull)
                            .max(LocalDateTime::compareTo)
                            .orElse(null);
                    return new MonitoringRow(
                            user.getUsername(),
                            reason,
                            metric,
                            "Review recent execution behavior",
                            formatDateTime(lastActive)
                    );
                })
                .filter(row -> row.reason().equals("High loss concentration") || extractLeadingDouble(row.metric()) >= 60.0)
                .sorted(Comparator.comparing(MonitoringRow::reason).thenComparing(MonitoringRow::metric, Comparator.reverseOrder()))
                .limit(6)
                .toList();
    }

    private List<DisabledUserRow> buildDisabledUsers(Collection<User> users, Map<String, List<Trade>> tradesByUser) {
        return users.stream()
                .filter(user -> !user.isActive())
                .map(user -> {
                    LocalDateTime lastTrade = tradesByUser.getOrDefault(user.getId(), List.of()).stream()
                            .map(this::resolveTradeCreatedAt)
                            .filter(Objects::nonNull)
                            .max(LocalDateTime::compareTo)
                            .orElse(null);
                    return new DisabledUserRow(
                            user.getUsername(),
                            formatDateTime(user.getUpdatedAt()),
                            formatDateTime(lastTrade),
                            "Disabled"
                    );
                })
                .sorted(Comparator.comparing(DisabledUserRow::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(8)
                .toList();
    }

    private AnalyticsSummary buildSummary(
            List<EngagementPoint> engagementTrend,
            List<UserAnalyticsRow> topActiveUsers,
            List<MonitoringRow> suspiciousUsers,
            int totalTrades
    ) {
        EngagementPoint latest = engagementTrend.isEmpty() ? new EngagementPoint("-", 0, 0, 0) : engagementTrend.get(engagementTrend.size() - 1);
        long topUserTrades = topActiveUsers.isEmpty() ? 0 : topActiveUsers.get(0).trades();
        return new AnalyticsSummary(latest.dau(), latest.wau(), latest.mau(), topUserTrades, suspiciousUsers.size(), totalTrades);
    }

    private List<CountPoint> topCountPoints(List<String> values, int limit) {
        return values.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .map(entry -> new CountPoint(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(CountPoint::value).reversed().thenComparing(CountPoint::label))
                .limit(limit)
                .toList();
    }

    private LocalDate resolveActivityDate(Trade trade) {
        LocalDateTime timestamp = resolveTradeCreatedAt(trade);
        return timestamp != null ? timestamp.toLocalDate() : null;
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

    private LocalDate startOfWeek(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private boolean isAdminUser(User user) {
        return user.getRole() != null && "ADMIN".equalsIgnoreCase(user.getRole());
    }

    private String normalizeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "N/A";
        }
        return value.trim();
    }

    private String normalizeResult(String value) {
        if (value == null || value.isBlank()) {
            return "BREAKEVEN";
        }
        if ("WIN".equalsIgnoreCase(value.trim())) {
            return "WIN";
        }
        if ("LOSS".equalsIgnoreCase(value.trim())) {
            return "LOSS";
        }
        return "BREAKEVEN";
    }

    private String normalizeSession(String value) {
        if (value == null || value.isBlank()) {
            return "OTHER";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String formatSessionLabel(String value) {
        return switch (value) {
            case "LONDON" -> "London";
            case "NEW_YORK" -> "New York";
            case "ASIA" -> "Asia";
            default -> "Other";
        };
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? "-" : value.format(DATE_TIME_FORMAT);
    }

    private int parseLeadingInt(String value) {
        if (value == null) {
            return 0;
        }
        String digits = value.split(" ")[0].replaceAll("[^0-9]", "");
        return digits.isBlank() ? 0 : Integer.parseInt(digits);
    }

    private double extractLeadingDouble(String value) {
        if (value == null) {
            return 0.0;
        }
        String numeric = value.split(" ")[0].replaceAll("[^0-9.]", "");
        return numeric.isBlank() ? 0.0 : Double.parseDouble(numeric);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record EngagementPoint(String label, long dau, long wau, long mau) {
    }

    private record CohortRow(String cohort, int users, List<Integer> values) {
    }

    private record CountPoint(String label, long value) {
    }

    private record ResultSlice(String label, long value, String color) {
    }

    private record SessionPerformancePoint(String label, double avgR, int trades, double totalPnl) {
    }

    private record UserAnalyticsRow(String user, long trades, double winRate, double pnl, String lastActive, LocalDateTime lastActiveRaw) {
    }

    private record InactiveUserRow(String user, String lastLogin, String lastTrade, String accountStatus, LocalDateTime lastTradeRaw) {
    }

    private record MonitoringRow(String user, String reason, String metric, String context, String lastActive) {
    }

    private record DisabledUserRow(String user, String updatedAt, String lastTrade, String status) {
    }

    private record AnalyticsSummary(long dau, long wau, long mau, long topUserTrades, int suspiciousUsers, int totalTrades) {
    }

    private record AdminAnalyticsData(
            List<EngagementPoint> engagementTrend,
            List<CohortRow> cohortRows,
            List<CountPoint> tradeDistribution,
            List<CountPoint> symbolPopularity,
            List<CountPoint> setupPopularity,
            List<CountPoint> mistakeFrequency,
            List<ResultSlice> resultDistribution,
            List<CountPoint> rDistribution,
            List<SessionPerformancePoint> sessionPerformance,
            List<UserAnalyticsRow> topActiveUsers,
            List<InactiveUserRow> inactiveUsers,
            List<MonitoringRow> abnormalUsers,
            List<MonitoringRow> excessiveDailyTradeUsers,
            List<MonitoringRow> suspiciousUsers,
            List<DisabledUserRow> disabledUsers,
            AnalyticsSummary summary
    ) {
    }
}
