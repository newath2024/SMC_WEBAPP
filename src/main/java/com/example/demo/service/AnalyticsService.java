package com.example.demo.service;

import com.example.demo.entity.Trade;
import com.example.demo.entity.TradeReview;
import com.example.demo.repository.TradeMistakeTagRepository;
import com.example.demo.repository.TradeReviewRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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

    public AnalyticsReport buildReportForUser(String userId, LocalDateTime from, LocalDateTime to) {
        List<Trade> trades = tradeService.findAllByUser(userId);
        List<Trade> filteredTrades = filterTradesByRange(trades, from, to);
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

    public TradeOverview buildOverviewForUser(String userId) {
        List<Trade> trades = tradeService.findAllByUser(userId);
        return buildOverview(trades);
    }

    public List<Trade> findTradesForUser(String userId, LocalDateTime from, LocalDateTime to) {
        List<Trade> trades = tradeService.findAllByUser(userId);
        return filterTradesByRange(trades, from, to);
    }

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
            totalR += trade.getRMultiple();
        }

        double winRate = totalTrades == 0 ? 0.0 : (winTrades * 100.0) / totalTrades;
        double avgPnl = totalTrades == 0 ? 0.0 : totalPnl / totalTrades;
        double avgR = totalTrades == 0 ? 0.0 : totalR / totalTrades;

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
            acc.totalR += trade.getRMultiple();
        }

        List<BreakdownRow> rows = new ArrayList<>();
        for (Map.Entry<String, GroupAccumulator> entry : grouped.entrySet()) {
            String label = entry.getKey();
            GroupAccumulator acc = entry.getValue();

            double winRate = acc.totalTrades == 0 ? 0.0 : (acc.winTrades * 100.0) / acc.totalTrades;
            double avgPnl = acc.totalTrades == 0 ? 0.0 : acc.totalPnl / acc.totalTrades;
            double avgR = acc.totalTrades == 0 ? 0.0 : acc.totalR / acc.totalTrades;

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
            runningR += trade.getRMultiple();
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
                followedPlanTrades++;
                followedPlanTotalR += trade.getRMultiple();
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
        private double totalPnl;
        private double totalR;
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
