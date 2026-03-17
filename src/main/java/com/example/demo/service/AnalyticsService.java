package com.example.demo.service;

import com.example.demo.entity.Trade;
import com.example.demo.entity.TradeMistakeTag;
import com.example.demo.entity.TradeReview;
import com.example.demo.repository.TradeMistakeTagRepository;
import com.example.demo.repository.TradeReviewRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
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
    private final ObjectMapper objectMapper;

    public AnalyticsService(
            TradeService tradeService,
            TradeReviewRepository tradeReviewRepository,
            TradeMistakeTagRepository tradeMistakeTagRepository
    ) {
        this.tradeService = tradeService;
        this.tradeReviewRepository = tradeReviewRepository;
        this.tradeMistakeTagRepository = tradeMistakeTagRepository;
        this.objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
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
        List<BreakdownRow> bySession = buildSessionBreakdown(filteredTrades);
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
        return buildWorkspaceReportForUser(userId, from, to, null, symbol, setup, null, null, null);
    }

    @Transactional(readOnly = true)
    public AnalyticsWorkspaceReport buildWorkspaceReportForUser(
            String userId,
            LocalDateTime from,
            LocalDateTime to,
            String symbol,
            String setup,
            String processScoreRange,
            String classification,
            String mistakeTag
    ) {
        return buildWorkspaceReportForUser(
                userId,
                from,
                to,
                null,
                symbol,
                setup,
                processScoreRange,
                classification,
                mistakeTag
        );
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
        return buildWorkspaceReportForUser(userId, from, to, account, symbol, setup, null, null, null);
    }

    @Transactional(readOnly = true)
    public AnalyticsWorkspaceReport buildWorkspaceReportForUser(
            String userId,
            LocalDateTime from,
            LocalDateTime to,
            String account,
            String symbol,
            String setup,
            String processScoreRange,
            String classification,
            String mistakeTag
    ) {
        List<Trade> allTrades = tradeService.findAllByUser(userId);
        Map<String, TradeReview> allReviewMap = buildReviewMap(allTrades);
        Map<String, TradeTagContext> allTagContexts = buildTradeTagContextMap(allTrades, allReviewMap);

        List<Trade> rangedTrades = filterTradesByRange(allTrades, from, to);
        List<Trade> baseFilteredTrades = filterTrades(rangedTrades, account, symbol, setup);
        Map<String, TradeReview> baseReviewMap = retainReviewMap(baseFilteredTrades, allReviewMap);
        Map<String, TradeTagContext> baseTagContexts = retainTagContextMap(baseFilteredTrades, allTagContexts);

        List<Trade> filteredTrades = filterTradesByAiFilters(
                baseFilteredTrades,
                baseReviewMap,
                baseTagContexts,
                processScoreRange,
                classification,
                mistakeTag
        );
        Map<String, TradeReview> filteredReviewMap = retainReviewMap(filteredTrades, baseReviewMap);
        Map<String, TradeTagContext> filteredTagContexts = retainTagContextMap(filteredTrades, baseTagContexts);

        List<StrategyBreakdownRow> strategyBreakdown = buildStrategyBreakdown(filteredTrades, filteredReviewMap);
        List<MistakeImpactRow> mistakeImpact = buildMistakeImpact(filteredTrades, filteredReviewMap, filteredTagContexts);
        List<SessionPerformanceRow> sessionPerformance = buildSessionPerformance(filteredTrades, filteredReviewMap);
        List<SymbolPerformanceRow> symbolPerformance = buildSymbolPerformance(filteredTrades, filteredReviewMap);
        List<ProcessOutcomeMatrixRow> processOutcomeMatrix = buildProcessOutcomeMatrix(filteredTrades, filteredReviewMap);
        StrategyEdgeScoreSummary strategyEdgeScore = buildStrategyEdgeScore(filteredTrades, filteredReviewMap);

        return new AnalyticsWorkspaceReport(
                buildWorkspaceSummary(filteredTrades),
                buildAiReviewInsights(filteredTrades, filteredReviewMap, filteredTagContexts, processOutcomeMatrix),
                strategyEdgeScore,
                buildRDistribution(filteredTrades),
                buildExpectancyBySetup(strategyBreakdown),
                mistakeImpact,
                buildProcessScoreDistribution(filteredTrades, filteredReviewMap),
                buildHoldingTimeDistribution(filteredTrades),
                sessionPerformance,
                buildDayOfWeekPerformance(filteredTrades),
                symbolPerformance,
                strategyBreakdown,
                processOutcomeMatrix,
                buildWhatToFixNext(mistakeImpact, sessionPerformance, strategyBreakdown, processOutcomeMatrix),
                extractDistinctAccounts(allTrades),
                extractDistinctSymbols(allTrades),
                extractDistinctSetups(allTrades),
                extractDistinctMistakeTags(allTagContexts)
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

    private WorkspaceSummary buildWorkspaceSummary(List<Trade> trades) {
        int totalTrades = trades.size();
        int winTrades = 0;
        int lossTrades = 0;
        double netPnl = 0.0;
        double totalWinPnl = 0.0;
        double totalLossPnl = 0.0;
        Double maxProfit = null;
        Double maxLoss = null;

        for (Trade trade : trades) {
            double pnl = trade.getPnl();
            netPnl += pnl;

            if (pnl > 0) {
                winTrades++;
                totalWinPnl += pnl;
                if (maxProfit == null || pnl > maxProfit) {
                    maxProfit = pnl;
                }
            }
            if (pnl < 0) {
                lossTrades++;
                totalLossPnl += pnl;
                if (maxLoss == null || pnl < maxLoss) {
                    maxLoss = pnl;
                }
            }
        }

        double avgPnl = totalTrades == 0 ? 0.0 : netPnl / totalTrades;
        Double avgWinPnl = winTrades == 0 ? null : round2(totalWinPnl / winTrades);
        Double avgLossPnl = lossTrades == 0 ? null : round2(totalLossPnl / lossTrades);

        return new WorkspaceSummary(
                totalTrades,
                winTrades,
                lossTrades,
                round2(netPnl),
                round2(avgPnl),
                avgWinPnl,
                avgLossPnl,
                maxProfit == null ? null : round2(maxProfit),
                maxLoss == null ? null : round2(maxLoss)
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

    private List<BreakdownRow> buildSessionBreakdown(List<Trade> trades) {
        return buildBreakdown(trades, Trade::getSession).stream()
                .map(row -> new BreakdownRow(
                        formatSessionLabel(row.getLabel()),
                        row.getTotalTrades(),
                        row.getWinTrades(),
                        row.getWinRate(),
                        row.getTotalPnl(),
                        row.getAvgPnl(),
                        row.getTotalR(),
                        row.getAvgR()
                ))
                .toList();
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

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double normalizeScore(double value, double min, double max) {
        if (max <= min) {
            return 0.0;
        }
        return clamp(((value - min) / (max - min)) * 100.0, 0.0, 100.0);
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

    private List<ExpectancyBySetupRow> buildExpectancyBySetup(List<StrategyBreakdownRow> rows) {
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

    private StrategyEdgeScoreSummary buildStrategyEdgeScore(
            List<Trade> trades,
            Map<String, TradeReview> reviewMap
    ) {
        Double expectancy = resolveExpectancyR(trades);
        StrategyEdgeReviewStats reviewStats = resolveStrategyEdgeReviewStats(trades, reviewMap);
        Double rStdDeviation = resolveRStdDeviation(trades);
        Double consistencyScore = rStdDeviation == null
                ? null
                : round2(100.0 - normalizeScore(rStdDeviation, 0.35, 2.0));

        Double expectancyScore = expectancy == null
                ? null
                : round2(normalizeScore(expectancy, -0.5, 1.0));
        Double avgProcessScore = reviewStats.avgProcessScore();
        Double badProcessRate = reviewStats.badProcessRate();
        Double badProcessDisciplineScore = badProcessRate == null
                ? null
                : round2(100.0 - clamp(badProcessRate, 0.0, 100.0));

        double weightedTotal = 0.0;
        double availableWeight = 0.0;

        if (expectancyScore != null) {
            weightedTotal += expectancyScore * 0.30;
            availableWeight += 0.30;
        }
        if (avgProcessScore != null) {
            weightedTotal += avgProcessScore * 0.30;
            availableWeight += 0.30;
        }
        if (consistencyScore != null) {
            weightedTotal += consistencyScore * 0.20;
            availableWeight += 0.20;
        }
        if (badProcessDisciplineScore != null) {
            weightedTotal += badProcessDisciplineScore * 0.20;
            availableWeight += 0.20;
        }

        int score = availableWeight == 0.0
                ? 0
                : (int) Math.round(clamp(weightedTotal / availableWeight, 0.0, 100.0));
        String label = resolveStrategyEdgeLabel(score);
        String labelClass = resolveStrategyEdgeLabelClass(score);

        return new StrategyEdgeScoreSummary(
                score,
                label,
                labelClass,
                "Edge reflects both performance and execution quality",
                expectancy == null ? null : round2(expectancy),
                avgProcessScore,
                rStdDeviation == null ? null : round2(rStdDeviation),
                badProcessRate,
                reviewStats.reviewedTrades(),
                reviewStats.knownRTrades()
        );
    }

    private Double resolveExpectancyR(List<Trade> trades) {
        int knownRTrades = 0;
        double totalR = 0.0;
        for (Trade trade : trades) {
            if (!trade.hasKnownRMultiple()) {
                continue;
            }
            knownRTrades++;
            totalR += trade.getRMultiple();
        }
        return knownRTrades == 0 ? null : round2(totalR / knownRTrades);
    }

    private Double resolveRStdDeviation(List<Trade> trades) {
        List<Double> rValues = new ArrayList<>();
        for (Trade trade : trades) {
            if (trade.hasKnownRMultiple()) {
                rValues.add(trade.getRMultiple());
            }
        }
        if (rValues.size() < 2) {
            return null;
        }

        double mean = 0.0;
        for (double rValue : rValues) {
            mean += rValue;
        }
        mean /= rValues.size();

        double variance = 0.0;
        for (double rValue : rValues) {
            double delta = rValue - mean;
            variance += delta * delta;
        }
        variance /= rValues.size();

        return Math.sqrt(variance);
    }

    private StrategyEdgeReviewStats resolveStrategyEdgeReviewStats(
            List<Trade> trades,
            Map<String, TradeReview> reviewMap
    ) {
        int reviewedTrades = 0;
        int badProcessTrades = 0;
        int knownRTrades = 0;
        double totalProcessScore = 0.0;

        for (Trade trade : trades) {
            if (trade.hasKnownRMultiple()) {
                knownRTrades++;
            }

            Integer processScore = resolveAiProcessScore(reviewMap.get(trade.getId()));
            if (processScore == null) {
                continue;
            }

            reviewedTrades++;
            totalProcessScore += processScore;
            if (processScore < 70) {
                badProcessTrades++;
            }
        }

        Double avgProcessScore = reviewedTrades == 0 ? null : round2(totalProcessScore / reviewedTrades);
        Double badProcessRate = reviewedTrades == 0 ? null : round2((badProcessTrades * 100.0) / reviewedTrades);
        return new StrategyEdgeReviewStats(avgProcessScore, badProcessRate, reviewedTrades, knownRTrades);
    }

    private String resolveStrategyEdgeLabel(int score) {
        if (score > 80) {
            return "Strong edge";
        }
        if (score >= 60) {
            return "Developing edge";
        }
        return "Weak / inconsistent";
    }

    private String resolveStrategyEdgeLabelClass(int score) {
        if (score > 80) {
            return "edge-strong";
        }
        if (score >= 60) {
            return "edge-developing";
        }
        return "edge-weak";
    }

    private AiReviewInsightsSummary buildAiReviewInsights(
            List<Trade> trades,
            Map<String, TradeReview> reviewMap,
            Map<String, TradeTagContext> tagContexts,
            List<ProcessOutcomeMatrixRow> processOutcomeMatrix
    ) {
        int reviewedTrades = 0;
        int goodProcessTrades = 0;
        int totalProcessScore = 0;
        Map<String, Integer> aiTagCounts = new HashMap<>();

        for (Trade trade : trades) {
            TradeReview review = reviewMap.get(trade.getId());
            Integer processScore = resolveAiProcessScore(review);
            if (processScore == null) {
                continue;
            }

            reviewedTrades++;
            totalProcessScore += processScore;
            if (processScore >= 70) {
                goodProcessTrades++;
            }

            TradeTagContext context = tagContexts.get(trade.getId());
            if (context == null) {
                continue;
            }
            for (String label : context.getAiTags()) {
                aiTagCounts.put(label, aiTagCounts.getOrDefault(label, 0) + 1);
            }
        }

        String mostFrequentAiMistakeTag = null;
        int mostFrequentAiMistakeTagCount = 0;
        for (Map.Entry<String, Integer> entry : aiTagCounts.entrySet()) {
            if (entry.getValue() > mostFrequentAiMistakeTagCount
                    || (entry.getValue() == mostFrequentAiMistakeTagCount
                    && mostFrequentAiMistakeTag != null
                    && entry.getKey().compareToIgnoreCase(mostFrequentAiMistakeTag) < 0)
                    || (entry.getValue() == mostFrequentAiMistakeTagCount && mostFrequentAiMistakeTag == null)) {
                mostFrequentAiMistakeTag = entry.getKey();
                mostFrequentAiMistakeTagCount = entry.getValue();
            }
        }

        Double avgProcessScore = reviewedTrades == 0 ? null : round2(totalProcessScore / (double) reviewedTrades);
        Double goodProcessRate = reviewedTrades == 0 ? null : round2((goodProcessTrades * 100.0) / reviewedTrades);

        return new AiReviewInsightsSummary(
                reviewedTrades,
                avgProcessScore,
                goodProcessRate,
                countMatrixTrades(processOutcomeMatrix, ProcessOutcomeClassification.BAD_PROCESS_GOOD_OUTCOME),
                mostFrequentAiMistakeTag,
                mostFrequentAiMistakeTagCount
        );
    }

    private List<MistakeImpactRow> buildMistakeImpact(
            List<Trade> trades,
            Map<String, TradeReview> reviewMap,
            Map<String, TradeTagContext> tagContexts
    ) {
        if (trades.isEmpty()) {
            return List.of();
        }

        Map<String, MistakeImpactAccumulator> aggregates = new HashMap<>();

        for (Trade trade : trades) {
            TradeTagContext context = tagContexts.get(trade.getId());
            if (context == null || context.isEmpty()) {
                continue;
            }

            Integer processScore = resolveAiProcessScore(reviewMap.get(trade.getId()));
            for (String manualTag : context.getManualTags()) {
                addMistakeImpact(aggregates, manualTag, trade, processScore, "Manual");
            }
            for (String aiTag : context.getAiTags()) {
                addMistakeImpact(aggregates, aiTag, trade, processScore, "AI");
            }
        }

        return aggregates.entrySet().stream()
                .map(entry -> new MistakeImpactRow(
                        entry.getKey(),
                        entry.getValue().getTrades(),
                        round2(entry.getValue().winRate()),
                        round2(entry.getValue().averageR()),
                        round2(entry.getValue().totalPnl),
                        entry.getValue().averageProcessScore(),
                        entry.getValue().resolveSourceLabel(),
                        entry.getValue().resolveSourceClass()
                ))
                .sorted(Comparator.comparingDouble((MistakeImpactRow row) -> Math.abs(row.getTotalPnl())).reversed()
                        .thenComparing(MistakeImpactRow::getLabel))
                .toList();
    }

    private void addMistakeImpact(
            Map<String, MistakeImpactAccumulator> aggregates,
            String label,
            Trade trade,
            Integer processScore,
            String sourceLabel
    ) {
        MistakeImpactAccumulator accumulator = aggregates.computeIfAbsent(
                normalizeLabel(label),
                key -> new MistakeImpactAccumulator()
        );
        accumulator.add(trade, processScore, sourceLabel);
    }

    private List<ProcessScoreDistributionRow> buildProcessScoreDistribution(
            List<Trade> trades,
            Map<String, TradeReview> reviewMap
    ) {
        List<ProcessScoreDistributionRow> rows = new ArrayList<>();
        List<ProcessScoreBucket> buckets = Arrays.asList(
                new ProcessScoreBucket("0-40", 0, 40),
                new ProcessScoreBucket("40-60", 40, 60),
                new ProcessScoreBucket("60-80", 60, 80),
                new ProcessScoreBucket("80-100", 80, 101)
        );

        for (ProcessScoreBucket bucket : buckets) {
            int count = 0;
            for (Trade trade : trades) {
                Integer processScore = resolveAiProcessScore(reviewMap.get(trade.getId()));
                if (processScore != null && bucket.matches(processScore)) {
                    count++;
                }
            }
            rows.add(new ProcessScoreDistributionRow(bucket.label(), count));
        }
        return rows;
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

    private List<SessionPerformanceRow> buildSessionPerformance(List<Trade> trades, Map<String, TradeReview> reviewMap) {
        Map<String, ReviewMetricAccumulator> aggregates = new LinkedHashMap<>();

        for (Trade trade : trades) {
            String label = formatSessionLabel(trade.getSession());
            ReviewMetricAccumulator accumulator = aggregates.computeIfAbsent(label, key -> new ReviewMetricAccumulator());
            accumulator.add(trade, resolveAiProcessScore(reviewMap.get(trade.getId())));
        }

        return aggregates.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> sessionSortOrder(entry.getKey())))
                .map(entry -> new SessionPerformanceRow(
                        entry.getKey(),
                        entry.getValue().trades,
                        round2(entry.getValue().averageR()),
                        round2(entry.getValue().totalPnl),
                        entry.getValue().averageProcessScore()
                ))
                .toList();
    }

    private String formatSessionLabel(String rawLabel) {
        if (rawLabel == null || rawLabel.isBlank()) {
            return "Other";
        }
        return switch (rawLabel.trim().toUpperCase(Locale.ROOT)) {
            case "ASIA" -> "Asia";
            case "LONDON" -> "London";
            case "NEW_YORK" -> "New York";
            case "OTHER" -> "Other";
            default -> rawLabel.replace('_', ' ');
        };
    }

    private int sessionSortOrder(String label) {
        return switch (label) {
            case "Asia" -> 0;
            case "London" -> 1;
            case "New York" -> 2;
            case "Other" -> 3;
            default -> 4;
        };
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

    private List<SymbolPerformanceRow> buildSymbolPerformance(List<Trade> trades, Map<String, TradeReview> reviewMap) {
        Map<String, ReviewMetricAccumulator> aggregates = new LinkedHashMap<>();

        for (Trade trade : trades) {
            String label = normalizeLabel(trade.getSymbol());
            ReviewMetricAccumulator accumulator = aggregates.computeIfAbsent(label, key -> new ReviewMetricAccumulator());
            accumulator.add(trade, resolveAiProcessScore(reviewMap.get(trade.getId())));
        }

        return aggregates.entrySet().stream()
                .map(entry -> new SymbolPerformanceRow(
                        entry.getKey(),
                        entry.getValue().trades,
                        round2(entry.getValue().winRate()),
                        round2(entry.getValue().averageR()),
                        round2(entry.getValue().totalPnl),
                        entry.getValue().averageProcessScore()
                ))
                .sorted(Comparator.comparingInt(SymbolPerformanceRow::getTrades).reversed()
                        .thenComparing(SymbolPerformanceRow::getLabel))
                .toList();
    }

    private List<StrategyBreakdownRow> buildStrategyBreakdown(List<Trade> trades, Map<String, TradeReview> reviewMap) {
        Map<String, ReviewMetricAccumulator> aggregates = new LinkedHashMap<>();

        for (Trade trade : trades) {
            String label = normalizeLabel(trade.getSetupName());
            ReviewMetricAccumulator accumulator = aggregates.computeIfAbsent(label, key -> new ReviewMetricAccumulator());
            accumulator.add(trade, resolveAiProcessScore(reviewMap.get(trade.getId())));
        }

        return aggregates.entrySet().stream()
                .map(entry -> new StrategyBreakdownRow(
                        entry.getKey(),
                        entry.getValue().trades,
                        round2(entry.getValue().winRate()),
                        round2(entry.getValue().averageR()),
                        round2(entry.getValue().averageR()),
                        entry.getValue().averageProcessScore(),
                        entry.getValue().badProcessRate(),
                        round2(entry.getValue().totalPnl)
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

    private Map<String, TradeTagContext> buildTradeTagContextMap(List<Trade> trades, Map<String, TradeReview> reviewMap) {
        Map<String, TradeTagContext> contexts = new HashMap<>();
        List<String> tradeIds = new ArrayList<>();

        for (Trade trade : trades) {
            if (trade.getId() == null || trade.getId().isBlank()) {
                continue;
            }
            contexts.put(trade.getId(), new TradeTagContext());
            tradeIds.add(trade.getId());
        }

        if (tradeIds.isEmpty()) {
            return Map.of();
        }

        for (TradeMistakeTag link : tradeMistakeTagRepository.findByTradeIdIn(tradeIds)) {
            if (link.getTrade() == null || link.getTrade().getId() == null) {
                continue;
            }
            String label = trimToNull(link.getMistakeTag() != null ? link.getMistakeTag().getName() : null);
            if (label == null) {
                continue;
            }
            contexts.computeIfAbsent(link.getTrade().getId(), key -> new TradeTagContext())
                    .addManualTag(normalizeLabel(label));
        }

        for (Map.Entry<String, TradeReview> entry : reviewMap.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            TradeTagContext context = contexts.computeIfAbsent(entry.getKey(), key -> new TradeTagContext());
            for (String label : readAiMistakeTags(entry.getValue())) {
                context.addAiTag(label);
            }
        }

        return contexts;
    }

    private Map<String, TradeReview> retainReviewMap(List<Trade> trades, Map<String, TradeReview> source) {
        if (trades.isEmpty() || source.isEmpty()) {
            return Map.of();
        }

        Map<String, TradeReview> retained = new HashMap<>();
        for (Trade trade : trades) {
            if (trade.getId() == null) {
                continue;
            }
            TradeReview review = source.get(trade.getId());
            if (review != null) {
                retained.put(trade.getId(), review);
            }
        }
        return retained;
    }

    private Map<String, TradeTagContext> retainTagContextMap(List<Trade> trades, Map<String, TradeTagContext> source) {
        if (trades.isEmpty() || source.isEmpty()) {
            return Map.of();
        }

        Map<String, TradeTagContext> retained = new HashMap<>();
        for (Trade trade : trades) {
            if (trade.getId() == null) {
                continue;
            }
            TradeTagContext context = source.get(trade.getId());
            if (context != null && !context.isEmpty()) {
                retained.put(trade.getId(), context);
            }
        }
        return retained;
    }

    private List<Trade> filterTradesByAiFilters(
            List<Trade> trades,
            Map<String, TradeReview> reviewMap,
            Map<String, TradeTagContext> tagContexts,
            String processScoreRange,
            String classification,
            String mistakeTag
    ) {
        String normalizedScoreRange = normalizeFilter(processScoreRange);
        ProcessOutcomeClassification classificationFilter = ProcessOutcomeClassification.fromValue(classification);
        String normalizedMistakeTag = normalizeFilter(mistakeTag);

        if (normalizedScoreRange == null && classificationFilter == null && normalizedMistakeTag == null) {
            return trades;
        }

        List<Trade> filtered = new ArrayList<>();
        for (Trade trade : trades) {
            TradeReview review = reviewMap.get(trade.getId());
            Integer processScore = resolveAiProcessScore(review);
            ProcessOutcomeClassification tradeClassification = resolveProcessOutcomeClassification(trade, review);
            TradeTagContext context = tagContexts.get(trade.getId());

            if (!matchesProcessScoreRange(processScore, normalizedScoreRange)) {
                continue;
            }
            if (classificationFilter != null && tradeClassification != classificationFilter) {
                continue;
            }
            if (normalizedMistakeTag != null && !tradeHasMistakeTag(context, normalizedMistakeTag)) {
                continue;
            }
            filtered.add(trade);
        }
        return filtered;
    }

    private boolean matchesProcessScoreRange(Integer processScore, String processScoreRange) {
        if (processScoreRange == null) {
            return true;
        }
        if (processScore == null) {
            return false;
        }
        return switch (processScoreRange) {
            case "0-40" -> processScore >= 0 && processScore < 40;
            case "40-60" -> processScore >= 40 && processScore < 60;
            case "60-80" -> processScore >= 60 && processScore < 80;
            case "80-100" -> processScore >= 80 && processScore <= 100;
            default -> true;
        };
    }

    private Integer resolveAiProcessScore(TradeReview review) {
        return review == null ? null : review.getQualityScore();
    }

    private ProcessOutcomeClassification resolveProcessOutcomeClassification(Trade trade, TradeReview review) {
        Integer processScore = resolveAiProcessScore(review);
        if (trade == null || processScore == null) {
            return null;
        }

        boolean goodProcess = processScore >= 70;
        boolean goodOutcome = trade.getPnl() > 0;

        if (goodProcess && goodOutcome) {
            return ProcessOutcomeClassification.GOOD_PROCESS_GOOD_OUTCOME;
        }
        if (goodProcess) {
            return ProcessOutcomeClassification.GOOD_PROCESS_BAD_OUTCOME;
        }
        if (goodOutcome) {
            return ProcessOutcomeClassification.BAD_PROCESS_GOOD_OUTCOME;
        }
        return ProcessOutcomeClassification.BAD_PROCESS_BAD_OUTCOME;
    }

    private List<ProcessOutcomeMatrixRow> buildProcessOutcomeMatrix(List<Trade> trades, Map<String, TradeReview> reviewMap) {
        Map<ProcessOutcomeClassification, Integer> counts = new LinkedHashMap<>();
        for (ProcessOutcomeClassification classification : ProcessOutcomeClassification.values()) {
            counts.put(classification, 0);
        }

        for (Trade trade : trades) {
            ProcessOutcomeClassification classification = resolveProcessOutcomeClassification(trade, reviewMap.get(trade.getId()));
            if (classification == null) {
                continue;
            }
            counts.put(classification, counts.get(classification) + 1);
        }

        List<ProcessOutcomeMatrixRow> rows = new ArrayList<>();
        for (ProcessOutcomeClassification classification : ProcessOutcomeClassification.values()) {
            rows.add(new ProcessOutcomeMatrixRow(
                    classification.name(),
                    classification.title(),
                    classification.subtitle(),
                    counts.getOrDefault(classification, 0),
                    classification.accentClass()
            ));
        }
        return rows;
    }

    private int countMatrixTrades(
            List<ProcessOutcomeMatrixRow> processOutcomeMatrix,
            ProcessOutcomeClassification classification
    ) {
        return processOutcomeMatrix.stream()
                .filter(row -> row.getKey().equals(classification.name()))
                .map(ProcessOutcomeMatrixRow::getCount)
                .findFirst()
                .orElse(0);
    }

    private boolean tradeHasMistakeTag(TradeTagContext context, String mistakeTag) {
        if (context == null || mistakeTag == null) {
            return false;
        }
        for (String tag : context.getAllTags()) {
            if (tag.equalsIgnoreCase(mistakeTag)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> readAiMistakeTags(TradeReview review) {
        return Set.of();
    }

    private List<CoachingInsightRow> buildWhatToFixNext(
            List<MistakeImpactRow> mistakeImpact,
            List<SessionPerformanceRow> sessionPerformance,
            List<StrategyBreakdownRow> strategyBreakdown,
            List<ProcessOutcomeMatrixRow> processOutcomeMatrix
    ) {
        List<CoachingInsightRow> insights = new ArrayList<>();
        Set<String> seenHeadlines = new HashSet<>();

        MistakeImpactRow topMistake = mistakeImpact.stream()
                .filter(row -> row.getTrades() >= 2 && row.getTotalPnl() < 0)
                .max(Comparator.comparingDouble(row -> Math.abs(row.getTotalPnl())))
                .orElse(null);
        if (topMistake != null) {
            addInsight(
                    insights,
                    seenHeadlines,
                    topMistake.getLabel() + " is the biggest repeat drag in losing trades.",
                    topMistake.getTrades() + " trades, " + formatSignedUsd(topMistake.getTotalPnl())
                            + " total PnL, avg process score " + formatProcessScore(topMistake.getAvgProcessScore()) + "."
            );
        }

        SessionPerformanceRow weakestSession = sessionPerformance.stream()
                .filter(row -> row.getTrades() >= 2 && row.getAvgProcessScore() != null)
                .min(Comparator.comparingDouble(SessionPerformanceRow::getAvgProcessScore))
                .orElse(null);
        if (weakestSession != null && weakestSession.getAvgProcessScore() < 70) {
            addInsight(
                    insights,
                    seenHeadlines,
                    "Process quality drops most during the " + weakestSession.getLabel() + " session.",
                    "Average process score " + formatProcessScore(weakestSession.getAvgProcessScore())
                            + " across " + weakestSession.getTrades() + " trades in this filter."
            );
        }

        StrategyBreakdownRow setupLeak = strategyBreakdown.stream()
                .filter(row -> row.getTrades() >= 2 && row.getBadProcessRate() != null && row.getAvgProcessScore() != null)
                .filter(row -> row.getBadProcessRate() >= 35.0)
                .max(Comparator.comparingDouble(StrategyBreakdownRow::getBadProcessRate)
                        .thenComparingInt(StrategyBreakdownRow::getTrades))
                .orElse(null);
        if (setupLeak != null) {
            addInsight(
                    insights,
                    seenHeadlines,
                    setupLeak.getLabel() + " still has too many low-quality executions.",
                    "Expectancy is " + round2(setupLeak.getExpectancy()) + "R, but bad process rate sits at "
                            + formatPercent(setupLeak.getBadProcessRate()) + "."
            );
        }

        int badProcessWinners = countMatrixTrades(processOutcomeMatrix, ProcessOutcomeClassification.BAD_PROCESS_GOOD_OUTCOME);
        if (badProcessWinners > 0) {
            addInsight(
                    insights,
                    seenHeadlines,
                    "Bad-process winners are reinforcing weak habits.",
                    badProcessWinners + " green trades still scored as bad process. Review these before they become your default pattern."
            );
        }

        if (insights.isEmpty()) {
            insights.add(new CoachingInsightRow(
                    "Not enough AI review coverage to prioritize fixes yet.",
                    "Generate more AI reviews in this filter range to unlock sharper coaching guidance."
            ));
        }

        return insights.stream().limit(3).toList();
    }

    private void addInsight(
            List<CoachingInsightRow> insights,
            Set<String> seenHeadlines,
            String headline,
            String detail
    ) {
        if (headline == null || headline.isBlank() || !seenHeadlines.add(headline)) {
            return;
        }
        insights.add(new CoachingInsightRow(headline, detail));
    }

    private String formatSignedUsd(double value) {
        return value >= 0
                ? "+$" + round2(value)
                : "-$" + round2(Math.abs(value));
    }

    private String formatPercent(Double value) {
        return value == null ? "N/A" : round2(value) + "%";
    }

    private String formatProcessScore(Double value) {
        return value == null ? "N/A" : round2(value) + "/100";
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<String> extractDistinctMistakeTags(Map<String, TradeTagContext> tagContexts) {
        Set<String> labels = new LinkedHashSet<>();
        for (TradeTagContext context : tagContexts.values()) {
            labels.addAll(context.getAllTags());
        }
        return labels.stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
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

    private static class ReviewMetricAccumulator {
        private int trades;
        private int winTrades;
        private int knownRTrades;
        private int reviewedTrades;
        private int badProcessTrades;
        private double totalPnl;
        private double totalR;
        private double totalProcessScore;

        private void add(Trade trade, Integer processScore) {
            trades++;
            totalPnl += trade.getPnl();
            if (trade.getPnl() > 0) {
                winTrades++;
            }
            if (trade.hasKnownRMultiple()) {
                knownRTrades++;
                totalR += trade.getRMultiple();
            }
            if (processScore != null) {
                reviewedTrades++;
                totalProcessScore += processScore;
                if (processScore < 70) {
                    badProcessTrades++;
                }
            }
        }

        private double averageR() {
            return knownRTrades == 0 ? 0.0 : totalR / knownRTrades;
        }

        private double winRate() {
            return trades == 0 ? 0.0 : (winTrades * 100.0) / trades;
        }

        private Double averageProcessScore() {
            return reviewedTrades == 0 ? null : Math.round((totalProcessScore / reviewedTrades) * 100.0) / 100.0;
        }

        private Double badProcessRate() {
            return reviewedTrades == 0 ? null : Math.round(((badProcessTrades * 100.0) / reviewedTrades) * 100.0) / 100.0;
        }
    }

    private record StrategyEdgeReviewStats(
            Double avgProcessScore,
            Double badProcessRate,
            int reviewedTrades,
            int knownRTrades
    ) {
    }

    private static class MistakeImpactAccumulator {
        private final ReviewMetricAccumulator metrics = new ReviewMetricAccumulator();
        private final Set<String> tradeIds = new HashSet<>();
        private final Set<String> sourceLabels = new HashSet<>();
        private double totalPnl;

        private void add(Trade trade, Integer processScore, String sourceLabel) {
            sourceLabels.add(sourceLabel);
            if (trade == null || trade.getId() == null || !tradeIds.add(trade.getId())) {
                return;
            }
            totalPnl += trade.getPnl();
            metrics.add(trade, processScore);
        }

        private int getTrades() {
            return metrics.trades;
        }

        private double winRate() {
            return metrics.winRate();
        }

        private double averageR() {
            return metrics.averageR();
        }

        private Double averageProcessScore() {
            return metrics.averageProcessScore();
        }

        private String resolveSourceLabel() {
            if (sourceLabels.contains("Manual") && sourceLabels.contains("AI")) {
                return "Mixed";
            }
            if (sourceLabels.contains("AI")) {
                return "AI";
            }
            return "Manual";
        }

        private String resolveSourceClass() {
            return switch (resolveSourceLabel()) {
                case "AI" -> "source-ai";
                case "Mixed" -> "source-mixed";
                default -> "source-manual";
            };
        }
    }

    private static class TradeTagContext {
        private final Set<String> manualTags = new LinkedHashSet<>();
        private final Set<String> aiTags = new LinkedHashSet<>();

        private void addManualTag(String label) {
            if (label != null && !label.isBlank()) {
                manualTags.add(label);
            }
        }

        private void addAiTag(String label) {
            if (label != null && !label.isBlank()) {
                aiTags.add(label);
            }
        }

        private Set<String> getManualTags() {
            return manualTags;
        }

        private Set<String> getAiTags() {
            return aiTags;
        }

        private Set<String> getAllTags() {
            Set<String> all = new LinkedHashSet<>(manualTags);
            all.addAll(aiTags);
            return all;
        }

        private boolean isEmpty() {
            return manualTags.isEmpty() && aiTags.isEmpty();
        }
    }

    private enum ProcessOutcomeClassification {
        GOOD_PROCESS_GOOD_OUTCOME(
                "Good process / Good outcome",
                "Repeatable edge with discipline.",
                "matrix-positive"
        ),
        GOOD_PROCESS_BAD_OUTCOME(
                "Good process / Bad outcome",
                "Valid execution, unfavorable result.",
                "matrix-neutral"
        ),
        BAD_PROCESS_GOOD_OUTCOME(
                "Bad process / Good outcome",
                "Danger zone: reward may reinforce bad habits.",
                "matrix-warning"
        ),
        BAD_PROCESS_BAD_OUTCOME(
                "Bad process / Bad outcome",
                "Highest priority for correction.",
                "matrix-danger"
        );

        private final String title;
        private final String subtitle;
        private final String accentClass;

        ProcessOutcomeClassification(String title, String subtitle, String accentClass) {
            this.title = title;
            this.subtitle = subtitle;
            this.accentClass = accentClass;
        }

        private String title() {
            return title;
        }

        private String subtitle() {
            return subtitle;
        }

        private String accentClass() {
            return accentClass;
        }

        private static ProcessOutcomeClassification fromValue(String value) {
            if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim())) {
                return null;
            }

            String normalized = value.trim().toUpperCase(Locale.ROOT)
                    .replace('/', '_')
                    .replace(' ', '_')
                    .replace('-', '_');

            for (ProcessOutcomeClassification candidate : values()) {
                if (candidate.name().equals(normalized)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    private record RRangeBucket(String label, double minInclusive, double maxExclusive) {
        private boolean matches(double value) {
            return value >= minInclusive && value < maxExclusive;
        }
    }

    private record ProcessScoreBucket(String label, int minInclusive, int maxExclusive) {
        private boolean matches(int value) {
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

    public static class WorkspaceSummary {
        private final int totalTrades;
        private final int winTrades;
        private final int lossTrades;
        private final double netPnl;
        private final double avgPnl;
        private final Double avgWinPnl;
        private final Double avgLossPnl;
        private final Double maxProfit;
        private final Double maxLoss;

        public WorkspaceSummary(
                int totalTrades,
                int winTrades,
                int lossTrades,
                double netPnl,
                double avgPnl,
                Double avgWinPnl,
                Double avgLossPnl,
                Double maxProfit,
                Double maxLoss
        ) {
            this.totalTrades = totalTrades;
            this.winTrades = winTrades;
            this.lossTrades = lossTrades;
            this.netPnl = netPnl;
            this.avgPnl = avgPnl;
            this.avgWinPnl = avgWinPnl;
            this.avgLossPnl = avgLossPnl;
            this.maxProfit = maxProfit;
            this.maxLoss = maxLoss;
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

        public double getNetPnl() {
            return netPnl;
        }

        public double getAvgPnl() {
            return avgPnl;
        }

        public Double getAvgWinPnl() {
            return avgWinPnl;
        }

        public Double getAvgLossPnl() {
            return avgLossPnl;
        }

        public Double getMaxProfit() {
            return maxProfit;
        }

        public Double getMaxLoss() {
            return maxLoss;
        }
    }

    public static class AnalyticsWorkspaceReport {
        private final WorkspaceSummary summary;
        private final AiReviewInsightsSummary aiReviewInsights;
        private final StrategyEdgeScoreSummary strategyEdgeScore;
        private final List<RDistributionRow> rDistribution;
        private final List<ExpectancyBySetupRow> expectancyBySetup;
        private final List<MistakeImpactRow> mistakeImpact;
        private final List<ProcessScoreDistributionRow> processScoreDistribution;
        private final List<HoldingTimeDistributionRow> holdingTimeDistribution;
        private final List<SessionPerformanceRow> sessionPerformance;
        private final List<DayOfWeekPerformanceRow> dayOfWeekPerformance;
        private final List<SymbolPerformanceRow> symbolPerformance;
        private final List<StrategyBreakdownRow> strategyBreakdown;
        private final List<ProcessOutcomeMatrixRow> processOutcomeMatrix;
        private final List<CoachingInsightRow> whatToFixNext;
        private final List<String> availableAccounts;
        private final List<String> availableSymbols;
        private final List<String> availableSetups;
        private final List<String> availableMistakeTags;

        public AnalyticsWorkspaceReport(
                WorkspaceSummary summary,
                AiReviewInsightsSummary aiReviewInsights,
                StrategyEdgeScoreSummary strategyEdgeScore,
                List<RDistributionRow> rDistribution,
                List<ExpectancyBySetupRow> expectancyBySetup,
                List<MistakeImpactRow> mistakeImpact,
                List<ProcessScoreDistributionRow> processScoreDistribution,
                List<HoldingTimeDistributionRow> holdingTimeDistribution,
                List<SessionPerformanceRow> sessionPerformance,
                List<DayOfWeekPerformanceRow> dayOfWeekPerformance,
                List<SymbolPerformanceRow> symbolPerformance,
                List<StrategyBreakdownRow> strategyBreakdown,
                List<ProcessOutcomeMatrixRow> processOutcomeMatrix,
                List<CoachingInsightRow> whatToFixNext,
                List<String> availableAccounts,
                List<String> availableSymbols,
                List<String> availableSetups,
                List<String> availableMistakeTags
        ) {
            this.summary = summary;
            this.aiReviewInsights = aiReviewInsights;
            this.strategyEdgeScore = strategyEdgeScore;
            this.rDistribution = rDistribution;
            this.expectancyBySetup = expectancyBySetup;
            this.mistakeImpact = mistakeImpact;
            this.processScoreDistribution = processScoreDistribution;
            this.holdingTimeDistribution = holdingTimeDistribution;
            this.sessionPerformance = sessionPerformance;
            this.dayOfWeekPerformance = dayOfWeekPerformance;
            this.symbolPerformance = symbolPerformance;
            this.strategyBreakdown = strategyBreakdown;
            this.processOutcomeMatrix = processOutcomeMatrix;
            this.whatToFixNext = whatToFixNext;
            this.availableAccounts = availableAccounts;
            this.availableSymbols = availableSymbols;
            this.availableSetups = availableSetups;
            this.availableMistakeTags = availableMistakeTags;
        }

        public WorkspaceSummary getSummary() {
            return summary;
        }

        public AiReviewInsightsSummary getAiReviewInsights() {
            return aiReviewInsights;
        }

        public StrategyEdgeScoreSummary getStrategyEdgeScore() {
            return strategyEdgeScore;
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

        public List<ProcessScoreDistributionRow> getProcessScoreDistribution() {
            return processScoreDistribution;
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

        public List<ProcessOutcomeMatrixRow> getProcessOutcomeMatrix() {
            return processOutcomeMatrix;
        }

        public List<CoachingInsightRow> getWhatToFixNext() {
            return whatToFixNext;
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

        public List<String> getAvailableMistakeTags() {
            return availableMistakeTags;
        }
    }

    public static class AiReviewInsightsSummary {
        private final int reviewedTrades;
        private final Double avgProcessScore;
        private final Double goodProcessRate;
        private final int badProcessGoodOutcomeCount;
        private final String mostFrequentAiMistakeTag;
        private final int mostFrequentAiMistakeTagCount;

        public AiReviewInsightsSummary(
                int reviewedTrades,
                Double avgProcessScore,
                Double goodProcessRate,
                int badProcessGoodOutcomeCount,
                String mostFrequentAiMistakeTag,
                int mostFrequentAiMistakeTagCount
        ) {
            this.reviewedTrades = reviewedTrades;
            this.avgProcessScore = avgProcessScore;
            this.goodProcessRate = goodProcessRate;
            this.badProcessGoodOutcomeCount = badProcessGoodOutcomeCount;
            this.mostFrequentAiMistakeTag = mostFrequentAiMistakeTag;
            this.mostFrequentAiMistakeTagCount = mostFrequentAiMistakeTagCount;
        }

        public int getReviewedTrades() {
            return reviewedTrades;
        }

        public Double getAvgProcessScore() {
            return avgProcessScore;
        }

        public Double getGoodProcessRate() {
            return goodProcessRate;
        }

        public int getBadProcessGoodOutcomeCount() {
            return badProcessGoodOutcomeCount;
        }

        public String getMostFrequentAiMistakeTag() {
            return mostFrequentAiMistakeTag;
        }

        public int getMostFrequentAiMistakeTagCount() {
            return mostFrequentAiMistakeTagCount;
        }
    }

    public static class StrategyEdgeScoreSummary {
        private final int score;
        private final String label;
        private final String labelClass;
        private final String explanation;
        private final Double expectancy;
        private final Double avgProcessScore;
        private final Double rStdDeviation;
        private final Double badProcessRate;
        private final int reviewedTrades;
        private final int knownRTrades;

        public StrategyEdgeScoreSummary(
                int score,
                String label,
                String labelClass,
                String explanation,
                Double expectancy,
                Double avgProcessScore,
                Double rStdDeviation,
                Double badProcessRate,
                int reviewedTrades,
                int knownRTrades
        ) {
            this.score = score;
            this.label = label;
            this.labelClass = labelClass;
            this.explanation = explanation;
            this.expectancy = expectancy;
            this.avgProcessScore = avgProcessScore;
            this.rStdDeviation = rStdDeviation;
            this.badProcessRate = badProcessRate;
            this.reviewedTrades = reviewedTrades;
            this.knownRTrades = knownRTrades;
        }

        public int getScore() {
            return score;
        }

        public String getLabel() {
            return label;
        }

        public String getLabelClass() {
            return labelClass;
        }

        public String getExplanation() {
            return explanation;
        }

        public Double getExpectancy() {
            return expectancy;
        }

        public Double getAvgProcessScore() {
            return avgProcessScore;
        }

        public Double getRStdDeviation() {
            return rStdDeviation;
        }

        public Double getBadProcessRate() {
            return badProcessRate;
        }

        public int getReviewedTrades() {
            return reviewedTrades;
        }

        public int getKnownRTrades() {
            return knownRTrades;
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
        private final double winRate;
        private final double avgR;
        private final double totalPnl;
        private final Double avgProcessScore;
        private final String sourceLabel;
        private final String sourceClass;

        public MistakeImpactRow(
                String label,
                int trades,
                double winRate,
                double avgR,
                double totalPnl,
                Double avgProcessScore,
                String sourceLabel,
                String sourceClass
        ) {
            this.label = label;
            this.trades = trades;
            this.winRate = winRate;
            this.avgR = avgR;
            this.totalPnl = totalPnl;
            this.avgProcessScore = avgProcessScore;
            this.sourceLabel = sourceLabel;
            this.sourceClass = sourceClass;
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

        public Double getAvgProcessScore() {
            return avgProcessScore;
        }

        public String getSourceLabel() {
            return sourceLabel;
        }

        public String getSourceClass() {
            return sourceClass;
        }
    }

    public static class ProcessScoreDistributionRow {
        private final String label;
        private final int count;

        public ProcessScoreDistributionRow(String label, int count) {
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
        private final Double avgProcessScore;

        public SessionPerformanceRow(String label, int trades, double avgR, double totalPnl, Double avgProcessScore) {
            this.label = label;
            this.trades = trades;
            this.avgR = avgR;
            this.totalPnl = totalPnl;
            this.avgProcessScore = avgProcessScore;
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

        public Double getAvgProcessScore() {
            return avgProcessScore;
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
        private final Double avgProcessScore;

        public SymbolPerformanceRow(
                String label,
                int trades,
                double winRate,
                double avgR,
                double totalPnl,
                Double avgProcessScore
        ) {
            this.label = label;
            this.trades = trades;
            this.winRate = winRate;
            this.avgR = avgR;
            this.totalPnl = totalPnl;
            this.avgProcessScore = avgProcessScore;
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

        public Double getAvgProcessScore() {
            return avgProcessScore;
        }
    }

    public static class StrategyBreakdownRow {
        private final String label;
        private final int trades;
        private final double winRate;
        private final double avgR;
        private final double expectancy;
        private final Double avgProcessScore;
        private final Double badProcessRate;
        private final double totalPnl;

        public StrategyBreakdownRow(
                String label,
                int trades,
                double winRate,
                double avgR,
                double expectancy,
                Double avgProcessScore,
                Double badProcessRate,
                double totalPnl
        ) {
            this.label = label;
            this.trades = trades;
            this.winRate = winRate;
            this.avgR = avgR;
            this.expectancy = expectancy;
            this.avgProcessScore = avgProcessScore;
            this.badProcessRate = badProcessRate;
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

        public Double getAvgProcessScore() {
            return avgProcessScore;
        }

        public Double getBadProcessRate() {
            return badProcessRate;
        }

        public double getTotalPnl() {
            return totalPnl;
        }
    }

    public static class ProcessOutcomeMatrixRow {
        private final String key;
        private final String title;
        private final String subtitle;
        private final int count;
        private final String accentClass;

        public ProcessOutcomeMatrixRow(String key, String title, String subtitle, int count, String accentClass) {
            this.key = key;
            this.title = title;
            this.subtitle = subtitle;
            this.count = count;
            this.accentClass = accentClass;
        }

        public String getKey() {
            return key;
        }

        public String getTitle() {
            return title;
        }

        public String getSubtitle() {
            return subtitle;
        }

        public int getCount() {
            return count;
        }

        public String getAccentClass() {
            return accentClass;
        }
    }

    public static class CoachingInsightRow {
        private final String headline;
        private final String detail;

        public CoachingInsightRow(String headline, String detail) {
            this.headline = headline;
            this.detail = detail;
        }

        public String getHeadline() {
            return headline;
        }

        public String getDetail() {
            return detail;
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
