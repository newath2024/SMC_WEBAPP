package com.example.demo.service;

import com.example.demo.entity.Trade;
import com.example.demo.entity.TradeReview;
import com.example.demo.repository.TradeMistakeTagRepository;
import com.example.demo.repository.TradeReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class AnalyticsService {

    private final TradeService tradeService;
    private final TradeReviewRepository tradeReviewRepository;
    private final TradeMistakeTagRepository tradeMistakeTagRepository;

    public AnalyticsService(
            TradeService tradeService,
            TradeReviewRepository tradeReviewRepository,
            TradeMistakeTagRepository tradeMistakeTagRepository
    ) {
        this.tradeService = tradeService;
        this.tradeReviewRepository = tradeReviewRepository;
        this.tradeMistakeTagRepository = tradeMistakeTagRepository;
    }

    public AnalyticsReport buildReportForUser(String userId) {
        return buildReportForUser(userId, null, null);
    }

    @Transactional(readOnly = true)
    public AnalyticsReport buildReportForUser(String userId, LocalDateTime from, LocalDateTime to) {
        return buildReportForUser(userId, from, to, null, null, null);
    }

    @Transactional(readOnly = true)
    public AnalyticsReport buildReportForUser(
            String userId,
            LocalDateTime from,
            LocalDateTime to,
            String account,
            String symbol,
            String setup
    ) {
        List<Trade> trades = tradeService.findAllByUser(userId);
        List<Trade> rangedTrades = filterTradesByRange(trades, from, to);
        List<Trade> filteredTrades = filterTrades(rangedTrades, account, symbol, setup);
        TradeOverview overview = buildOverview(filteredTrades);
        RiskMetrics riskMetrics = buildRiskMetrics(filteredTrades);
        ProcessMetrics processMetrics = buildProcessMetrics(filteredTrades);

        List<BreakdownRow> bySetup = buildBreakdown(filteredTrades, Trade::getSetupName);
        List<BreakdownRow> bySession = buildBreakdown(filteredTrades, Trade::getSession);
        List<BreakdownRow> bySymbol = buildBreakdown(filteredTrades, Trade::getSymbol);
        List<TrendPoint> equityCurve = buildEquityCurve(filteredTrades);
        List<MistakeFrequencyRow> mistakeFrequency = buildMistakeFrequency(filteredTrades);

        return new AnalyticsReport(
                overview,
                riskMetrics,
                processMetrics,
                bySetup,
                bySession,
                bySymbol,
                equityCurve,
                mistakeFrequency
        );
    }

    @Transactional(readOnly = true)
    public AnalyticsWorkspaceReport buildWorkspaceReportForUser(
            String userId,
            LocalDateTime from,
            LocalDateTime to,
            String symbol,
            String setup
    ) {
        return buildWorkspaceReportForUser(userId, from, to, null, symbol, setup);
    }

    @Transactional(readOnly = true)
    public AnalyticsWorkspaceReport buildWorkspaceReportForUser(
            String userId,
            LocalDateTime from,
            LocalDateTime to,
            String account,
            String symbol,
            String setup
    ) {
        List<Trade> allTrades = tradeService.findAllByUser(userId);
        List<Trade> rangedTrades = filterTradesByRange(allTrades, from, to);
        List<Trade> filteredTrades = filterTrades(rangedTrades, account, symbol, setup);

        Map<String, TradeReview> reviewMap = buildReviewMap(filteredTrades);

        return new AnalyticsWorkspaceReport(
                buildRDistribution(filteredTrades),
                buildExpectancyBySetup(filteredTrades),
                buildMistakeImpact(filteredTrades, reviewMap),
                buildHoldingTimeDistribution(filteredTrades),
                buildSessionPerformance(filteredTrades),
                buildDayOfWeekPerformance(filteredTrades),
                buildSymbolPerformance(filteredTrades),
                buildStrategyBreakdown(filteredTrades),
                extractDistinctAccounts(allTrades),
                extractDistinctSymbols(allTrades),
                extractDistinctSetups(allTrades)
        );
    }

    @Transactional(readOnly = true)
    public TradeOverview buildOverviewForUser(String userId) {
        List<Trade> trades = tradeService.findAllByUser(userId);
        return buildOverview(trades);
    }

    @Transactional(readOnly = true)
    public List<Trade> findTradesForUser(String userId, LocalDateTime from, LocalDateTime to) {
        return findTradesForUser(userId, from, to, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<Trade> findTradesForUser(
            String userId,
            LocalDateTime from,
            LocalDateTime to,
            String account,
            String symbol,
            String setup
    ) {
        List<Trade> trades = tradeService.findAllByUser(userId);
        List<Trade> rangedTrades = filterTradesByRange(trades, from, to);
        return filterTrades(rangedTrades, account, symbol, setup);
    }

    private List<Trade> filterTrades(List<Trade> trades, String account, String symbol, String setup) {
        String normalizedAccount = normalizeFilter(account);
        String normalizedSymbol = normalizeFilter(symbol);
        String normalizedSetup = normalizeFilter(setup);
        if (normalizedAccount == null && normalizedSymbol == null && normalizedSetup == null) {
            return trades;
        }

        List<Trade> filtered = new ArrayList<>();
        for (Trade trade : trades) {
            if (normalizedAccount != null && !normalizedAccount.equalsIgnoreCase(normalizeLabel(trade.getAccountLabel()))) {
                continue;
            }
            if (normalizedSymbol != null && !normalizedSymbol.equalsIgnoreCase(normalizeLabel(trade.getSymbol()))) {
                continue;
            }
            if (normalizedSetup != null && !normalizedSetup.equalsIgnoreCase(normalizeLabel(trade.getSetupName()))) {
                continue;
            }
            filtered.add(trade);
        }
        return filtered;
    }

    @Transactional(readOnly = true)
    public PeriodComparison buildPeriodComparisonForUser(String userId, LocalDateTime currentFrom, LocalDateTime currentTo) {
        if (currentFrom == null || currentTo == null || currentFrom.isAfter(currentTo)) {
            return null;
        }

        List<Trade> allTrades = tradeService.findAllByUser(userId);
        List<Trade> currentTrades = filterTradesByRange(allTrades, currentFrom, currentTo);

        Duration currentDuration = Duration.between(currentFrom, currentTo);
        LocalDateTime previousTo = currentFrom.minusNanos(1);
        LocalDateTime previousFrom = previousTo.minus(currentDuration);

        List<Trade> previousTrades = filterTradesByRange(allTrades, previousFrom, previousTo);

        TradeOverview currentOverview = buildOverview(currentTrades);
        TradeOverview previousOverview = buildOverview(previousTrades);
        RiskMetrics currentRisk = buildRiskMetrics(currentTrades);
        RiskMetrics previousRisk = buildRiskMetrics(previousTrades);

        return new PeriodComparison(
                currentOverview,
                previousOverview,
                currentOverview.getTotalTrades() - previousOverview.getTotalTrades(),
                round2(currentOverview.getWinRate() - previousOverview.getWinRate()),
                round2(currentOverview.getTotalPnl() - previousOverview.getTotalPnl()),
                round2(currentOverview.getAvgPnl() - previousOverview.getAvgPnl()),
                round2(currentOverview.getTotalR() - previousOverview.getTotalR()),
                round2(currentOverview.getAvgR() - previousOverview.getAvgR()),
                calculateNullableDelta(currentRisk.getProfitFactor(), previousRisk.getProfitFactor()),
                round2(currentRisk.getMaxDrawdown() - previousRisk.getMaxDrawdown())
        );
    }

    private Double calculateNullableDelta(Double currentValue, Double previousValue) {
        if (currentValue == null || previousValue == null) {
            return null;
        }
        return round2(currentValue - previousValue);
    }

    private TradeOverview buildOverview(List<Trade> trades) {
        int totalTrades = trades.size();
        int winTrades = 0;
        int lossTrades = 0;
        int beTrades = 0;
        int knownRTrades = 0;

        double totalPnl = 0.0;
        double totalR = 0.0;

        for (Trade trade : trades) {
            if ("WIN".equalsIgnoreCase(trade.getResult())) {
                winTrades++;
            } else if ("LOSS".equalsIgnoreCase(trade.getResult())) {
                lossTrades++;
            } else if ("BE and take partial".equalsIgnoreCase(trade.getResult())) {
                beTrades++;
            }

            totalPnl += trade.getPnl();
            if (trade.hasKnownRMultiple()) {
                knownRTrades++;
                totalR += trade.getRMultiple();
            }
        }

        double winRate = totalTrades == 0 ? 0.0 : (winTrades * 100.0) / totalTrades;
        double avgPnl = totalTrades == 0 ? 0.0 : totalPnl / totalTrades;
        double avgR = knownRTrades == 0 ? 0.0 : totalR / knownRTrades;

        return new TradeOverview(
                totalTrades,
                winTrades,
                lossTrades,
                beTrades,
                round2(winRate),
                round2(totalPnl),
                round2(avgPnl),
                round2(totalR),
                round2(avgR)
        );
    }

    private List<BreakdownRow> buildBreakdown(List<Trade> trades, LabelResolver labelResolver) {
        Map<String, GroupAccumulator> grouped = new LinkedHashMap<>();

        for (Trade trade : trades) {
            String rawLabel = labelResolver.resolveLabel(trade);
            String label = normalizeLabel(rawLabel);

            GroupAccumulator acc = grouped.computeIfAbsent(label, key -> new GroupAccumulator());
            acc.totalTrades++;
            if ("WIN".equalsIgnoreCase(trade.getResult())) {
                acc.winTrades++;
            }
            acc.totalPnl += trade.getPnl();
            if (trade.hasKnownRMultiple()) {
                acc.knownRTrades++;
                acc.totalR += trade.getRMultiple();
            }
        }

        List<BreakdownRow> rows = new ArrayList<>();
        for (Map.Entry<String, GroupAccumulator> entry : grouped.entrySet()) {
            String label = entry.getKey();
            GroupAccumulator acc = entry.getValue();

            double winRate = acc.totalTrades == 0 ? 0.0 : (acc.winTrades * 100.0) / acc.totalTrades;
            double avgPnl = acc.totalTrades == 0 ? 0.0 : acc.totalPnl / acc.totalTrades;
            double avgR = acc.knownRTrades == 0 ? 0.0 : acc.totalR / acc.knownRTrades;

            rows.add(new BreakdownRow(
                    label,
                    acc.totalTrades,
                    acc.winTrades,
                    round2(winRate),
                    round2(acc.totalPnl),
                    round2(avgPnl),
                    round2(acc.totalR),
                    round2(avgR)
            ));
        }

        rows.sort(Comparator.comparingInt(BreakdownRow::getTotalTrades).reversed()
                .thenComparing(BreakdownRow::getLabel));
        return rows;
    }

    private String normalizeLabel(String rawLabel) {
        if (rawLabel == null || rawLabel.isBlank()) {
            return "N/A";
        }
        return rawLabel.trim();
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private List<Trade> filterTradesByRange(List<Trade> trades, LocalDateTime from, LocalDateTime to) {
        if (from == null && to == null) {
            return trades;
        }

        List<Trade> filtered = new ArrayList<>();
        for (Trade trade : trades) {
            LocalDateTime timestamp = resolveTradeTimestamp(trade);
            if (timestamp == null) {
                continue;
            }
            if (from != null && timestamp.isBefore(from)) {
                continue;
            }
            if (to != null && timestamp.isAfter(to)) {
                continue;
            }
            filtered.add(trade);
        }
        return filtered;
    }

    private LocalDateTime resolveTradeTimestamp(Trade trade) {
        if (trade.getEntryTime() != null) {
            return trade.getEntryTime();
        }
        if (trade.getTradeDate() != null) {
            return trade.getTradeDate();
        }
        return trade.getCreatedAt();
    }

    private List<TrendPoint> buildEquityCurve(List<Trade> trades) {
        List<Trade> sorted = new ArrayList<>(trades);
        sorted.sort(Comparator
                .comparing(Trade::getEntryTime, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(Trade::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(Trade::getId, Comparator.nullsLast(String::compareTo)));

        List<TrendPoint> points = new ArrayList<>();
        double runningPnl = 0.0;
        double runningR = 0.0;
        double peakPnl = 0.0;

        for (Trade trade : sorted) {
            runningPnl += trade.getPnl();
            if (trade.hasKnownRMultiple()) {
                runningR += trade.getRMultiple();
            }
            peakPnl = Math.max(peakPnl, runningPnl);
            points.add(new TrendPoint(
                    buildTradePointLabel(trade),
                    round2(runningPnl),
                    round2(runningR),
                    round2(peakPnl - runningPnl)
            ));
        }

        return points;
    }

    private List<MistakeFrequencyRow> buildMistakeFrequency(List<Trade> trades) {
        if (trades.isEmpty()) {
            return List.of();
        }

        List<String> tradeIds = trades.stream()
                .map(Trade::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        Map<String, Integer> counts = new HashMap<>();

        if (!tradeIds.isEmpty()) {
            tradeMistakeTagRepository.findByTradeIdIn(tradeIds).forEach(link -> {
                if (link.getMistakeTag() == null || link.getMistakeTag().getName() == null) {
                    return;
                }
                String label = normalizeLabel(link.getMistakeTag().getName());
                counts.put(label, counts.getOrDefault(label, 0) + 1);
            });
        }

        List<TradeReview> reviews = tradeReviewRepository.findByTradeIdIn(tradeIds);
        for (TradeReview review : reviews) {
            if (Boolean.TRUE.equals(review.getHadFomo())) {
                counts.put("FOMO", counts.getOrDefault("FOMO", 0) + 1);
            }
            if (Boolean.TRUE.equals(review.getEnteredBeforeNews())) {
                counts.put("Before news", counts.getOrDefault("Before news", 0) + 1);
            }
        }

        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(6)
                .map(entry -> new MistakeFrequencyRow(entry.getKey(), entry.getValue()))
                .toList();
    }

    private List<RDistributionRow> buildRDistribution(List<Trade> trades) {
        List<RDistributionRow> buckets = new ArrayList<>();
        List<RRangeBucket> definitions = Arrays.asList(
                new RRangeBucket("<= -2R", Double.NEGATIVE_INFINITY, -2.0),
                new RRangeBucket("-2R to -1R", -2.0, -1.0),
                new RRangeBucket("-1R to 0R", -1.0, 0.0),
                new RRangeBucket("0R to 1R", 0.0, 1.0),
                new RRangeBucket("1R to 2R", 1.0, 2.0),
                new RRangeBucket("2R to 3R", 2.0, 3.0),
                new RRangeBucket(">= 3R", 3.0, Double.POSITIVE_INFINITY)
        );

        for (RRangeBucket bucket : definitions) {
            int count = 0;
            for (Trade trade : trades) {
                if (!trade.hasKnownRMultiple()) {
                    continue;
                }
                double value = trade.getRMultiple();
                if (bucket.matches(value)) {
                    count++;
                }
            }
            buckets.add(new RDistributionRow(bucket.label(), count));
        }
        return buckets;
    }

    private List<ExpectancyBySetupRow> buildExpectancyBySetup(List<Trade> trades) {
        List<StrategyBreakdownRow> rows = buildStrategyBreakdown(trades);
        return rows.stream()
                .sorted(Comparator.comparingDouble(StrategyBreakdownRow::getExpectancy).reversed())
                .map(row -> new ExpectancyBySetupRow(
                        row.getLabel(),
                        row.getTrades(),
                        row.getExpectancy(),
                        row.getTotalPnl()
                ))
                .toList();
    }

    private List<MistakeImpactRow> buildMistakeImpact(List<Trade> trades, Map<String, TradeReview> reviewMap) {
        if (trades.isEmpty()) {
            return List.of();
        }

        Map<String, MetricAccumulator> aggregates = new HashMap<>();
        List<String> tradeIds = trades.stream()
                .map(Trade::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();

        if (!tradeIds.isEmpty()) {
            tradeMistakeTagRepository.findByTradeIdIn(tradeIds).forEach(link -> {
                Trade trade = link.getTrade();
                if (trade == null || trade.getId() == null) {
                    return;
                }
                String label = link.getMistakeTag() != null ? link.getMistakeTag().getName() : null;
                addMetricAggregate(aggregates, normalizeLabel(label), trade);
            });
        }

        for (Trade trade : trades) {
            TradeReview review = reviewMap.get(trade.getId());
            if (review == null) {
                continue;
            }
            if (Boolean.TRUE.equals(review.getHadFomo())) {
                addMetricAggregate(aggregates, "FOMO", trade);
            }
            if (Boolean.TRUE.equals(review.getEnteredBeforeNews())) {
                addMetricAggregate(aggregates, "Before News", trade);
            }
            Integer timingRating = review.getEntryTimingRating();
            if (timingRating != null && timingRating <= 2) {
                addMetricAggregate(aggregates, "Early Entry", trade);
            }
            if (Boolean.FALSE.equals(review.getFollowedPlan())) {
                addMetricAggregate(aggregates, "Plan Violation", trade);
            }
        }

        return aggregates.entrySet().stream()
                .map(entry -> new MistakeImpactRow(
                        entry.getKey(),
                        entry.getValue().count,
                        round2(entry.getValue().averageR()),
                        round2(entry.getValue().totalPnl)
                ))
                .sorted(Comparator.comparingDouble((MistakeImpactRow row) -> Math.abs(row.getTotalPnl())).reversed()
                        .thenComparing(MistakeImpactRow::getLabel))
                .toList();
    }

    private List<HoldingTimeDistributionRow> buildHoldingTimeDistribution(List<Trade> trades) {
        List<HoldingBucketDefinition> definitions = Arrays.asList(
                new HoldingBucketDefinition("<5 min", 0, 5),
                new HoldingBucketDefinition("5-15 min", 5, 15),
                new HoldingBucketDefinition("15-60 min", 15, 60),
                new HoldingBucketDefinition("1-4h", 60, 240),
                new HoldingBucketDefinition(">4h", 240, Long.MAX_VALUE)
        );
        List<HoldingTimeDistributionRow> rows = new ArrayList<>();

        for (HoldingBucketDefinition definition : definitions) {
            MetricAccumulator acc = new MetricAccumulator();
            for (Trade trade : trades) {
                Long holdingMinutes = trade.getHoldingMinutes();
                if (holdingMinutes == null || !definition.matches(holdingMinutes)) {
                    continue;
                }
                acc.add(trade);
            }
            rows.add(new HoldingTimeDistributionRow(
                    definition.label(),
                    acc.count,
                    round2(acc.averageR())
            ));
        }
        return rows;
    }

    private List<SessionPerformanceRow> buildSessionPerformance(List<Trade> trades) {
        List<BreakdownRow> rows = buildBreakdown(trades, Trade::getSession);
        return rows.stream()
                .map(row -> new SessionPerformanceRow(
                        row.getLabel(),
                        row.getTotalTrades(),
                        row.getAvgR(),
                        row.getTotalPnl()
                ))
                .toList();
    }

    private List<DayOfWeekPerformanceRow> buildDayOfWeekPerformance(List<Trade> trades) {
        Map<DayOfWeek, MetricAccumulator> aggregates = new LinkedHashMap<>();
        for (DayOfWeek dayOfWeek : DayOfWeek.values()) {
            aggregates.put(dayOfWeek, new MetricAccumulator());
        }

        for (Trade trade : trades) {
            LocalDateTime timestamp = resolveTradeTimestamp(trade);
            if (timestamp == null) {
                continue;
            }
            aggregates.get(timestamp.getDayOfWeek()).add(trade);
        }

        List<DayOfWeekPerformanceRow> rows = new ArrayList<>();
        for (DayOfWeek dayOfWeek : DayOfWeek.values()) {
            MetricAccumulator acc = aggregates.get(dayOfWeek);
            rows.add(new DayOfWeekPerformanceRow(
                    dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                    dayOfWeek.getValue(),
                    acc.count,
                    round2(acc.averageR()),
                    round2(acc.totalPnl)
            ));
        }
        return rows;
    }

    private List<SymbolPerformanceRow> buildSymbolPerformance(List<Trade> trades) {
        List<BreakdownRow> rows = buildBreakdown(trades, Trade::getSymbol);
        return rows.stream()
                .map(row -> new SymbolPerformanceRow(
                        row.getLabel(),
                        row.getTotalTrades(),
                        row.getWinRate(),
                        row.getAvgR(),
                        row.getTotalPnl()
                ))
                .toList();
    }

    private List<StrategyBreakdownRow> buildStrategyBreakdown(List<Trade> trades) {
        List<BreakdownRow> rows = buildBreakdown(trades, Trade::getSetupName);
        return rows.stream()
                .map(row -> new StrategyBreakdownRow(
                        row.getLabel(),
                        row.getTotalTrades(),
                        row.getWinRate(),
                        row.getAvgR(),
                        row.getAvgR(),
                        row.getTotalPnl()
                ))
                .sorted(Comparator.comparingDouble(StrategyBreakdownRow::getExpectancy).reversed())
                .toList();
    }

    private Map<String, TradeReview> buildReviewMap(List<Trade> trades) {
        List<String> tradeIds = trades.stream()
                .map(Trade::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (tradeIds.isEmpty()) {
            return Map.of();
        }

        Map<String, TradeReview> reviewMap = new HashMap<>();
        for (TradeReview review : tradeReviewRepository.findByTradeIdIn(tradeIds)) {
            if (review.getTrade() != null && review.getTrade().getId() != null) {
                reviewMap.put(review.getTrade().getId(), review);
            }
        }
        return reviewMap;
    }

    private void addMetricAggregate(Map<String, MetricAccumulator> aggregates, String label, Trade trade) {
        MetricAccumulator accumulator = aggregates.computeIfAbsent(label, key -> new MetricAccumulator());
        accumulator.add(trade);
    }

    private List<String> extractDistinctSymbols(List<Trade> trades) {
        return trades.stream()
                .map(Trade::getSymbol)
                .map(this::normalizeLabel)
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> extractDistinctAccounts(List<Trade> trades) {
        return trades.stream()
                .map(Trade::getAccountLabel)
                .map(this::normalizeLabel)
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> extractDistinctSetups(List<Trade> trades) {
        return trades.stream()
                .map(Trade::getSetupName)
                .map(this::normalizeLabel)
                .distinct()
                .sorted()
                .toList();
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return value.trim();
    }

    private RiskMetrics buildRiskMetrics(List<Trade> trades) {
        int totalTrades = trades.size();
        int winTrades = 0;
        int lossTrades = 0;
        double grossProfit = 0.0;
        double grossLossAbs = 0.0;

        for (Trade trade : trades) {
            double pnl = trade.getPnl();
            if (pnl > 0) {
                winTrades++;
                grossProfit += pnl;
            } else if (pnl < 0) {
                lossTrades++;
                grossLossAbs += Math.abs(pnl);
            }
        }

        double avgWin = winTrades == 0 ? 0.0 : grossProfit / winTrades;
        double avgLoss = lossTrades == 0 ? 0.0 : -(grossLossAbs / lossTrades);
        double winRate = totalTrades == 0 ? 0.0 : (winTrades * 1.0) / totalTrades;
        double lossRate = totalTrades == 0 ? 0.0 : (lossTrades * 1.0) / totalTrades;
        double expectancy = (winRate * avgWin) + (lossRate * avgLoss);
        double maxDrawdown = calculateMaxDrawdown(trades);
        Double profitFactor = grossLossAbs == 0.0 ? null : grossProfit / grossLossAbs;

        return new RiskMetrics(
                profitFactor == null ? null : round2(profitFactor),
                round2(maxDrawdown),
                round2(expectancy),
                round2(avgWin),
                round2(avgLoss)
        );
    }

    private ProcessMetrics buildProcessMetrics(List<Trade> trades) {
        if (trades.isEmpty()) {
            return new ProcessMetrics(0, 0, 0.0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }

        List<String> tradeIds = trades.stream().map(Trade::getId).toList();
        List<TradeReview> reviews = tradeReviewRepository.findByTradeIdIn(tradeIds);
        Map<String, TradeReview> reviewMap = new HashMap<>();
        for (TradeReview review : reviews) {
            if (review.getTrade() != null && review.getTrade().getId() != null) {
                reviewMap.put(review.getTrade().getId(), review);
            }
        }

        int reviewedTrades = 0;
        int highQualityTrades = 0;
        int highQualityWins = 0;
        int followedPlanTrades = 0;
        double followedPlanTotalR = 0.0;
        int badProcessWins = 0;
        int ruleViolations = 0;
        int gradeA = 0;
        int gradeB = 0;
        int gradeC = 0;
        int goodProcessGoodOutcome = 0;
        int goodProcessBadOutcome = 0;
        int badProcessGoodOutcome = 0;
        int badProcessBadOutcome = 0;
        List<ProcessTradeRow> badProcessWinTrades = new ArrayList<>();

        for (Trade trade : trades) {
            TradeReview review = reviewMap.get(trade.getId());
            if (review == null || review.getQualityScore() == null) {
                continue;
            }

            reviewedTrades++;
            if (hasRuleViolation(review)) {
                ruleViolations++;
            }
            boolean highQuality = review.getQualityScore() >= 70;
            boolean tradeWin = trade.getPnl() > 0;

            if (review.getQualityScore() >= 85) {
                gradeA++;
            } else if (review.getQualityScore() >= 70) {
                gradeB++;
            } else {
                gradeC++;
            }

            if (highQuality) {
                highQualityTrades++;
                if (tradeWin) {
                    highQualityWins++;
                    goodProcessGoodOutcome++;
                } else {
                    goodProcessBadOutcome++;
                }
            } else {
                if (tradeWin) {
                    badProcessWins++;
                    badProcessGoodOutcome++;
                    badProcessWinTrades.add(new ProcessTradeRow(
                            trade.getId(),
                            trade.getSymbol(),
                            trade.getPnl(),
                            review.getQualityScore(),
                            trade.getEntryTime()
                    ));
                } else {
                    badProcessBadOutcome++;
                }
            }

            if (Boolean.TRUE.equals(review.getFollowedPlan())) {
                if (trade.hasKnownRMultiple()) {
                    followedPlanTrades++;
                    followedPlanTotalR += trade.getRMultiple();
                }
            }
        }

        double reviewedRate = trades.isEmpty() ? 0.0 : (reviewedTrades * 100.0) / trades.size();
        double highQualityWinRate = highQualityTrades == 0 ? 0.0 : (highQualityWins * 100.0) / highQualityTrades;
        double avgRFollowedPlan = followedPlanTrades == 0 ? 0.0 : followedPlanTotalR / followedPlanTrades;

        return new ProcessMetrics(
                reviewedTrades,
                highQualityTrades,
                round2(reviewedRate),
                round2(highQualityWinRate),
                round2(avgRFollowedPlan),
                badProcessWins,
                ruleViolations,
                gradeA,
                gradeB,
                gradeC,
                goodProcessGoodOutcome,
                goodProcessBadOutcome,
                badProcessGoodOutcome,
                badProcessBadOutcome,
                badProcessWinTrades
        );
    }

    private boolean hasRuleViolation(TradeReview review) {
        return Boolean.FALSE.equals(review.getFollowedPlan())
                || Boolean.FALSE.equals(review.getRespectedRisk())
                || Boolean.FALSE.equals(review.getAlignedHtfBias())
                || Boolean.FALSE.equals(review.getCorrectSession())
                || Boolean.FALSE.equals(review.getCorrectSetup())
                || Boolean.FALSE.equals(review.getCorrectPoi())
                || Boolean.TRUE.equals(review.getHadFomo())
                || Boolean.TRUE.equals(review.getEnteredBeforeNews());
    }

    private double calculateMaxDrawdown(List<Trade> trades) {
        List<Trade> sorted = new ArrayList<>(trades);
        sorted.sort(Comparator
                .comparing(Trade::getEntryTime, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(Trade::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(Trade::getId, Comparator.nullsLast(String::compareTo)));

        double equity = 0.0;
        double peak = 0.0;
        double maxDrawdown = 0.0;

        for (Trade trade : sorted) {
            equity += trade.getPnl();
            if (equity > peak) {
                peak = equity;
            }
            double drawdown = peak - equity;
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown;
            }
        }

        return maxDrawdown;
    }

    private String buildTradePointLabel(Trade trade) {
        LocalDateTime entry = trade.getEntryTime();
        if (entry == null) {
            return trade.getId();
        }
        return entry.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    @FunctionalInterface
    private interface LabelResolver {
        String resolveLabel(Trade trade);
    }

    private static class GroupAccumulator {
        private int totalTrades;
        private int winTrades;
        private int knownRTrades;
        private double totalPnl;
        private double totalR;
    }

    private static class MetricAccumulator {
        private int count;
        private int knownRTrades;
        private double totalPnl;
        private double totalR;

        private void add(Trade trade) {
            count++;
            totalPnl += trade.getPnl();
            if (trade.hasKnownRMultiple()) {
                knownRTrades++;
                totalR += trade.getRMultiple();
            }
        }

        private double averageR() {
            return knownRTrades == 0 ? 0.0 : totalR / knownRTrades;
        }
    }

    private record RRangeBucket(String label, double minInclusive, double maxExclusive) {
        private boolean matches(double value) {
            return value >= minInclusive && value < maxExclusive;
        }
    }

    private record HoldingBucketDefinition(String label, long minInclusive, long maxExclusive) {
        private boolean matches(long value) {
            return value >= minInclusive && value < maxExclusive;
        }
    }

    public static class AnalyticsReport {
        private final TradeOverview overview;
        private final RiskMetrics riskMetrics;
        private final ProcessMetrics processMetrics;
        private final List<BreakdownRow> bySetup;
        private final List<BreakdownRow> bySession;
        private final List<BreakdownRow> bySymbol;
        private final List<TrendPoint> equityCurve;
        private final List<MistakeFrequencyRow> mistakeFrequency;

        public AnalyticsReport(
                TradeOverview overview,
                RiskMetrics riskMetrics,
                ProcessMetrics processMetrics,
                List<BreakdownRow> bySetup,
                List<BreakdownRow> bySession,
                List<BreakdownRow> bySymbol,
                List<TrendPoint> equityCurve,
                List<MistakeFrequencyRow> mistakeFrequency
        ) {
            this.overview = overview;
            this.riskMetrics = riskMetrics;
            this.processMetrics = processMetrics;
            this.bySetup = bySetup;
            this.bySession = bySession;
            this.bySymbol = bySymbol;
            this.equityCurve = equityCurve;
            this.mistakeFrequency = mistakeFrequency;
        }

        public TradeOverview getOverview() {
            return overview;
        }

        public RiskMetrics getRiskMetrics() {
            return riskMetrics;
        }

        public ProcessMetrics getProcessMetrics() {
            return processMetrics;
        }

        public List<BreakdownRow> getBySetup() {
            return bySetup;
        }

        public List<BreakdownRow> getBySession() {
            return bySession;
        }

        public List<BreakdownRow> getBySymbol() {
            return bySymbol;
        }

        public List<TrendPoint> getEquityCurve() {
            return equityCurve;
        }

        public List<MistakeFrequencyRow> getMistakeFrequency() {
            return mistakeFrequency;
        }
    }

    public static class AnalyticsWorkspaceReport {
        private final List<RDistributionRow> rDistribution;
        private final List<ExpectancyBySetupRow> expectancyBySetup;
        private final List<MistakeImpactRow> mistakeImpact;
        private final List<HoldingTimeDistributionRow> holdingTimeDistribution;
        private final List<SessionPerformanceRow> sessionPerformance;
        private final List<DayOfWeekPerformanceRow> dayOfWeekPerformance;
        private final List<SymbolPerformanceRow> symbolPerformance;
        private final List<StrategyBreakdownRow> strategyBreakdown;
        private final List<String> availableAccounts;
        private final List<String> availableSymbols;
        private final List<String> availableSetups;

        public AnalyticsWorkspaceReport(
                List<RDistributionRow> rDistribution,
                List<ExpectancyBySetupRow> expectancyBySetup,
                List<MistakeImpactRow> mistakeImpact,
                List<HoldingTimeDistributionRow> holdingTimeDistribution,
                List<SessionPerformanceRow> sessionPerformance,
                List<DayOfWeekPerformanceRow> dayOfWeekPerformance,
                List<SymbolPerformanceRow> symbolPerformance,
                List<StrategyBreakdownRow> strategyBreakdown,
                List<String> availableAccounts,
                List<String> availableSymbols,
                List<String> availableSetups
        ) {
            this.rDistribution = rDistribution;
            this.expectancyBySetup = expectancyBySetup;
            this.mistakeImpact = mistakeImpact;
            this.holdingTimeDistribution = holdingTimeDistribution;
            this.sessionPerformance = sessionPerformance;
            this.dayOfWeekPerformance = dayOfWeekPerformance;
            this.symbolPerformance = symbolPerformance;
            this.strategyBreakdown = strategyBreakdown;
            this.availableAccounts = availableAccounts;
            this.availableSymbols = availableSymbols;
            this.availableSetups = availableSetups;
        }

        public List<RDistributionRow> getRDistribution() {
            return rDistribution;
        }

        public List<ExpectancyBySetupRow> getExpectancyBySetup() {
            return expectancyBySetup;
        }

        public List<MistakeImpactRow> getMistakeImpact() {
            return mistakeImpact;
        }

        public List<HoldingTimeDistributionRow> getHoldingTimeDistribution() {
            return holdingTimeDistribution;
        }

        public List<SessionPerformanceRow> getSessionPerformance() {
            return sessionPerformance;
        }

        public List<DayOfWeekPerformanceRow> getDayOfWeekPerformance() {
            return dayOfWeekPerformance;
        }

        public List<SymbolPerformanceRow> getSymbolPerformance() {
            return symbolPerformance;
        }

        public List<StrategyBreakdownRow> getStrategyBreakdown() {
            return strategyBreakdown;
        }

        public List<String> getAvailableAccounts() {
            return availableAccounts;
        }

        public List<String> getAvailableSymbols() {
            return availableSymbols;
        }

        public List<String> getAvailableSetups() {
            return availableSetups;
        }
    }

    public static class RDistributionRow {
        private final String label;
        private final int count;

        public RDistributionRow(String label, int count) {
            this.label = label;
            this.count = count;
        }

        public String getLabel() {
            return label;
        }

        public int getCount() {
            return count;
        }
    }

    public static class ExpectancyBySetupRow {
        private final String label;
        private final int trades;
        private final double expectancy;
        private final double totalPnl;

        public ExpectancyBySetupRow(String label, int trades, double expectancy, double totalPnl) {
            this.label = label;
            this.trades = trades;
            this.expectancy = expectancy;
            this.totalPnl = totalPnl;
        }

        public String getLabel() {
            return label;
        }

        public int getTrades() {
            return trades;
        }

        public double getExpectancy() {
            return expectancy;
        }

        public double getTotalPnl() {
            return totalPnl;
        }
    }

    public static class MistakeImpactRow {
        private final String label;
        private final int trades;
        private final double avgR;
        private final double totalPnl;

        public MistakeImpactRow(String label, int trades, double avgR, double totalPnl) {
            this.label = label;
            this.trades = trades;
            this.avgR = avgR;
            this.totalPnl = totalPnl;
        }

        public String getLabel() {
            return label;
        }

        public int getTrades() {
            return trades;
        }

        public double getAvgR() {
            return avgR;
        }

        public double getTotalPnl() {
            return totalPnl;
        }
    }

    public static class HoldingTimeDistributionRow {
        private final String label;
        private final int trades;
        private final double avgR;

        public HoldingTimeDistributionRow(String label, int trades, double avgR) {
            this.label = label;
            this.trades = trades;
            this.avgR = avgR;
        }

        public String getLabel() {
            return label;
        }

        public int getTrades() {
            return trades;
        }

        public double getAvgR() {
            return avgR;
        }
    }

    public static class SessionPerformanceRow {
        private final String label;
        private final int trades;
        private final double avgR;
        private final double totalPnl;

        public SessionPerformanceRow(String label, int trades, double avgR, double totalPnl) {
            this.label = label;
            this.trades = trades;
            this.avgR = avgR;
            this.totalPnl = totalPnl;
        }

        public String getLabel() {
            return label;
        }

        public int getTrades() {
            return trades;
        }

        public double getAvgR() {
            return avgR;
        }

        public double getTotalPnl() {
            return totalPnl;
        }
    }

    public static class DayOfWeekPerformanceRow {
        private final String label;
        private final int orderIndex;
        private final int trades;
        private final double avgR;
        private final double totalPnl;

        public DayOfWeekPerformanceRow(String label, int orderIndex, int trades, double avgR, double totalPnl) {
            this.label = label;
            this.orderIndex = orderIndex;
            this.trades = trades;
            this.avgR = avgR;
            this.totalPnl = totalPnl;
        }

        public String getLabel() {
            return label;
        }

        public int getOrderIndex() {
            return orderIndex;
        }

        public int getTrades() {
            return trades;
        }

        public double getAvgR() {
            return avgR;
        }

        public double getTotalPnl() {
            return totalPnl;
        }
    }

    public static class SymbolPerformanceRow {
        private final String label;
        private final int trades;
        private final double winRate;
        private final double avgR;
        private final double totalPnl;

        public SymbolPerformanceRow(String label, int trades, double winRate, double avgR, double totalPnl) {
            this.label = label;
            this.trades = trades;
            this.winRate = winRate;
            this.avgR = avgR;
            this.totalPnl = totalPnl;
        }

        public String getLabel() {
            return label;
        }

        public int getTrades() {
            return trades;
        }

        public double getWinRate() {
            return winRate;
        }

        public double getAvgR() {
            return avgR;
        }

        public double getTotalPnl() {
            return totalPnl;
        }
    }

    public static class StrategyBreakdownRow {
        private final String label;
        private final int trades;
        private final double winRate;
        private final double avgR;
        private final double expectancy;
        private final double totalPnl;

        public StrategyBreakdownRow(String label, int trades, double winRate, double avgR, double expectancy, double totalPnl) {
            this.label = label;
            this.trades = trades;
            this.winRate = winRate;
            this.avgR = avgR;
            this.expectancy = expectancy;
            this.totalPnl = totalPnl;
        }

        public String getLabel() {
            return label;
        }

        public int getTrades() {
            return trades;
        }

        public double getWinRate() {
            return winRate;
        }

        public double getAvgR() {
            return avgR;
        }

        public double getExpectancy() {
            return expectancy;
        }

        public double getTotalPnl() {
            return totalPnl;
        }
    }

    public static class PeriodComparison {
        private final TradeOverview current;
        private final TradeOverview previous;
        private final int totalTradesDelta;
        private final double winRateDelta;
        private final double totalPnlDelta;
        private final double avgPnlDelta;
        private final double totalRDelta;
        private final double avgRDelta;
        private final Double profitFactorDelta;
        private final double maxDrawdownDelta;

        public PeriodComparison(
                TradeOverview current,
                TradeOverview previous,
                int totalTradesDelta,
                double winRateDelta,
                double totalPnlDelta,
                double avgPnlDelta,
                double totalRDelta,
                double avgRDelta,
                Double profitFactorDelta,
                double maxDrawdownDelta
        ) {
            this.current = current;
            this.previous = previous;
            this.totalTradesDelta = totalTradesDelta;
            this.winRateDelta = winRateDelta;
            this.totalPnlDelta = totalPnlDelta;
            this.avgPnlDelta = avgPnlDelta;
            this.totalRDelta = totalRDelta;
            this.avgRDelta = avgRDelta;
            this.profitFactorDelta = profitFactorDelta;
            this.maxDrawdownDelta = maxDrawdownDelta;
        }

        public TradeOverview getCurrent() {
            return current;
        }

        public TradeOverview getPrevious() {
            return previous;
        }

        public int getTotalTradesDelta() {
            return totalTradesDelta;
        }

        public double getWinRateDelta() {
            return winRateDelta;
        }

        public double getTotalPnlDelta() {
            return totalPnlDelta;
        }

        public double getAvgPnlDelta() {
            return avgPnlDelta;
        }

        public double getTotalRDelta() {
            return totalRDelta;
        }

        public double getAvgRDelta() {
            return avgRDelta;
        }

        public Double getProfitFactorDelta() {
            return profitFactorDelta;
        }

        public double getMaxDrawdownDelta() {
            return maxDrawdownDelta;
        }
    }

    public static class RiskMetrics {
        private final Double profitFactor;
        private final double maxDrawdown;
        private final double expectancy;
        private final double avgWin;
        private final double avgLoss;

        public RiskMetrics(
                Double profitFactor,
                double maxDrawdown,
                double expectancy,
                double avgWin,
                double avgLoss
        ) {
            this.profitFactor = profitFactor;
            this.maxDrawdown = maxDrawdown;
            this.expectancy = expectancy;
            this.avgWin = avgWin;
            this.avgLoss = avgLoss;
        }

        public Double getProfitFactor() {
            return profitFactor;
        }

        public double getMaxDrawdown() {
            return maxDrawdown;
        }

        public double getExpectancy() {
            return expectancy;
        }

        public double getAvgWin() {
            return avgWin;
        }

        public double getAvgLoss() {
            return avgLoss;
        }
    }

    public static class ProcessMetrics {
        private final int reviewedTrades;
        private final int highQualityTrades;
        private final double reviewedRate;
        private final double highQualityWinRate;
        private final double avgRFollowedPlan;
        private final int badProcessWins;
        private final int ruleViolations;
        private final int gradeA;
        private final int gradeB;
        private final int gradeC;
        private final int goodProcessGoodOutcome;
        private final int goodProcessBadOutcome;
        private final int badProcessGoodOutcome;
        private final int badProcessBadOutcome;
        private final List<ProcessTradeRow> badProcessWinTrades;

        public ProcessMetrics(
                int reviewedTrades,
                int highQualityTrades,
                double reviewedRate,
                double highQualityWinRate,
                double avgRFollowedPlan,
                int badProcessWins,
                int ruleViolations,
                int gradeA,
                int gradeB,
                int gradeC,
                int goodProcessGoodOutcome,
                int goodProcessBadOutcome,
                int badProcessGoodOutcome,
                int badProcessBadOutcome,
                List<ProcessTradeRow> badProcessWinTrades
        ) {
            this.reviewedTrades = reviewedTrades;
            this.highQualityTrades = highQualityTrades;
            this.reviewedRate = reviewedRate;
            this.highQualityWinRate = highQualityWinRate;
            this.avgRFollowedPlan = avgRFollowedPlan;
            this.badProcessWins = badProcessWins;
            this.ruleViolations = ruleViolations;
            this.gradeA = gradeA;
            this.gradeB = gradeB;
            this.gradeC = gradeC;
            this.goodProcessGoodOutcome = goodProcessGoodOutcome;
            this.goodProcessBadOutcome = goodProcessBadOutcome;
            this.badProcessGoodOutcome = badProcessGoodOutcome;
            this.badProcessBadOutcome = badProcessBadOutcome;
            this.badProcessWinTrades = badProcessWinTrades;
        }

        public int getReviewedTrades() {
            return reviewedTrades;
        }

        public int getHighQualityTrades() {
            return highQualityTrades;
        }

        public double getReviewedRate() {
            return reviewedRate;
        }

        public double getHighQualityWinRate() {
            return highQualityWinRate;
        }

        public double getAvgRFollowedPlan() {
            return avgRFollowedPlan;
        }

        public int getBadProcessWins() {
            return badProcessWins;
        }

        public int getRuleViolations() {
            return ruleViolations;
        }

        public int getGradeA() {
            return gradeA;
        }

        public int getGradeB() {
            return gradeB;
        }

        public int getGradeC() {
            return gradeC;
        }

        public int getGoodProcessGoodOutcome() {
            return goodProcessGoodOutcome;
        }

        public int getGoodProcessBadOutcome() {
            return goodProcessBadOutcome;
        }

        public int getBadProcessGoodOutcome() {
            return badProcessGoodOutcome;
        }

        public int getBadProcessBadOutcome() {
            return badProcessBadOutcome;
        }

        public List<ProcessTradeRow> getBadProcessWinTrades() {
            return badProcessWinTrades;
        }
    }

    public static class ProcessTradeRow {
        private final String tradeId;
        private final String symbol;
        private final double pnl;
        private final int qualityScore;
        private final LocalDateTime entryTime;

        public ProcessTradeRow(String tradeId, String symbol, double pnl, int qualityScore, LocalDateTime entryTime) {
            this.tradeId = tradeId;
            this.symbol = symbol;
            this.pnl = pnl;
            this.qualityScore = qualityScore;
            this.entryTime = entryTime;
        }

        public String getTradeId() {
            return tradeId;
        }

        public String getSymbol() {
            return symbol;
        }

        public double getPnl() {
            return pnl;
        }

        public int getQualityScore() {
            return qualityScore;
        }

        public LocalDateTime getEntryTime() {
            return entryTime;
        }
    }

    public static class BreakdownRow {
        private final String label;
        private final int totalTrades;
        private final int winTrades;
        private final double winRate;
        private final double totalPnl;
        private final double avgPnl;
        private final double totalR;
        private final double avgR;

        public BreakdownRow(
                String label,
                int totalTrades,
                int winTrades,
                double winRate,
                double totalPnl,
                double avgPnl,
                double totalR,
                double avgR
        ) {
            this.label = label;
            this.totalTrades = totalTrades;
            this.winTrades = winTrades;
            this.winRate = winRate;
            this.totalPnl = totalPnl;
            this.avgPnl = avgPnl;
            this.totalR = totalR;
            this.avgR = avgR;
        }

        public String getLabel() {
            return label;
        }

        public int getTotalTrades() {
            return totalTrades;
        }

        public int getWinTrades() {
            return winTrades;
        }

        public double getWinRate() {
            return winRate;
        }

        public double getTotalPnl() {
            return totalPnl;
        }

        public double getAvgPnl() {
            return avgPnl;
        }

        public double getTotalR() {
            return totalR;
        }

        public double getAvgR() {
            return avgR;
        }
    }

    public static class TrendPoint {
        private final String label;
        private final double value;
        private final double rValue;
        private final double drawdown;

        public TrendPoint(String label, double value, double rValue, double drawdown) {
            this.label = label;
            this.value = value;
            this.rValue = rValue;
            this.drawdown = drawdown;
        }

        public String getLabel() {
            return label;
        }

        public double getValue() {
            return value;
        }

        public double getRValue() {
            return rValue;
        }

        public double getDrawdown() {
            return drawdown;
        }
    }

    public static class MistakeFrequencyRow {
        private final String label;
        private final int count;

        public MistakeFrequencyRow(String label, int count) {
            this.label = label;
            this.count = count;
        }

        public String getLabel() {
            return label;
        }

        public int getCount() {
            return count;
        }
    }

    public static class TradeOverview {
        private final int totalTrades;
        private final int winTrades;
        private final int lossTrades;
        private final int beTrades;
        private final double winRate;
        private final double totalPnl;
        private final double avgPnl;
        private final double totalR;
        private final double avgR;

        public TradeOverview(
                int totalTrades,
                int winTrades,
                int lossTrades,
                int beTrades,
                double winRate,
                double totalPnl,
                double avgPnl,
                double totalR,
                double avgR
        ) {
            this.totalTrades = totalTrades;
            this.winTrades = winTrades;
            this.lossTrades = lossTrades;
            this.beTrades = beTrades;
            this.winRate = winRate;
            this.totalPnl = totalPnl;
            this.avgPnl = avgPnl;
            this.totalR = totalR;
            this.avgR = avgR;
        }

        public int getTotalTrades() {
            return totalTrades;
        }

        public int getWinTrades() {
            return winTrades;
        }

        public int getLossTrades() {
            return lossTrades;
        }

        public int getBeTrades() {
            return beTrades;
        }

        public double getWinRate() {
            return winRate;
        }

        public double getTotalPnl() {
            return totalPnl;
        }

        public double getAvgPnl() {
            return avgPnl;
        }

        public double getTotalR() {
            return totalR;
        }

        public double getAvgR() {
            return avgR;
        }
    }
}
