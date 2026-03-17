package com.example.demo.service;

import com.example.demo.entity.MistakeTag;
import com.example.demo.entity.Trade;
import com.example.demo.entity.TradeReview;
import com.example.demo.repository.TradeReviewRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class MistakeAnalyticsService {

    private final TradeService tradeService;
    private final TradeReviewRepository tradeReviewRepository;

    public MistakeAnalyticsService(
            TradeService tradeService,
            TradeReviewRepository tradeReviewRepository
    ) {
        this.tradeService = tradeService;
        this.tradeReviewRepository = tradeReviewRepository;
    }

    public MistakeTrendReport buildTrendReportForUser(String userId) {
        List<Trade> trades = new ArrayList<>(tradeService.findAllByUser(userId));
        trades.sort(Comparator.comparing(this::resolveTradeTimestamp, Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, TradeReview> reviewByTradeId = loadReviewMap(trades);
        List<Trade> recent30Trades = trades.stream().limit(30).toList();
        List<Trade> recent10Trades = recent30Trades.stream().limit(10).toList();
        List<Trade> previous10Trades = recent30Trades.stream().skip(10).limit(10).toList();

        MistakeCountSnapshot overallSnapshot = accumulateCounts(trades, reviewByTradeId);
        MistakeCountSnapshot recent30Snapshot = accumulateCounts(recent30Trades, reviewByTradeId);
        MistakeCountSnapshot recent10Snapshot = accumulateCounts(recent10Trades, reviewByTradeId);
        MistakeCountSnapshot previous10Snapshot = accumulateCounts(previous10Trades, reviewByTradeId);

        List<TopMistakeTrendView> topMistakesLast30 = buildTopMistakeViews(recent30Snapshot, recent10Snapshot, previous10Snapshot);
        List<DistributionPoint> distributionPoints = buildDistributionPoints(overallSnapshot);

        long recent30UsageTotal = recent30Snapshot.counts().values().stream()
                .mapToLong(Long::longValue)
                .sum();
        TopMistakeTrendView mostFrequentMistake = topMistakesLast30.isEmpty() ? null : topMistakesLast30.get(0);
        String mostFrequentMistakeName = mostFrequentMistake == null ? "N/A" : mostFrequentMistake.name();
        long mostFrequentMistakeUsage = mostFrequentMistake == null ? 0L : mostFrequentMistake.usageCount();
        long mostFrequentUsagePercent = toPercent(mostFrequentMistakeUsage, recent30UsageTotal);

        return new MistakeTrendReport(
                mostFrequentMistakeName,
                mostFrequentMistakeUsage,
                mostFrequentUsagePercent,
                topMistakesLast30,
                distributionPoints,
                buildQuickInsight(recent30Snapshot, recent10Snapshot, previous10Snapshot)
        );
    }

    private Map<String, TradeReview> loadReviewMap(List<Trade> trades) {
        List<String> tradeIds = trades.stream()
                .map(Trade::getId)
                .filter(Objects::nonNull)
                .toList();
        if (tradeIds.isEmpty()) {
            return Map.of();
        }

        return tradeReviewRepository.findByTradeIdIn(tradeIds).stream()
                .filter(review -> review.getTrade() != null && review.getTrade().getId() != null)
                .collect(Collectors.toMap(
                        review -> review.getTrade().getId(),
                        review -> review,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));
    }

    private MistakeCountSnapshot accumulateCounts(List<Trade> trades, Map<String, TradeReview> reviewByTradeId) {
        if (trades == null || trades.isEmpty()) {
            return MistakeCountSnapshot.empty();
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();

        for (Trade trade : trades) {
            Map<String, String> combinedMistakes = resolveCombinedMistakes(trade, reviewByTradeId.get(trade.getId()));
            combinedMistakes.forEach((key, label) -> {
                labels.putIfAbsent(key, label);
                counts.merge(key, 1L, Long::sum);
            });
        }

        return new MistakeCountSnapshot(counts, labels);
    }

    private Map<String, String> resolveCombinedMistakes(Trade trade, TradeReview review) {
        Map<String, String> combined = new LinkedHashMap<>();

        if (trade != null && trade.getMistakes() != null) {
            for (MistakeTag mistakeTag : trade.getMistakes()) {
                addMistakeLabel(combined, mistakeTag != null ? mistakeTag.getName() : null);
            }
        }

        return combined;
    }

    private void addMistakeLabel(Map<String, String> combined, String rawLabel) {
        String key = normalizeMistakeKey(rawLabel);
        if (key == null) {
            return;
        }
        combined.putIfAbsent(key, presentMistakeLabel(rawLabel));
    }

    private String normalizeMistakeKey(String rawLabel) {
        if (rawLabel == null) {
            return null;
        }
        String collapsed = rawLabel.trim()
                .replace('_', ' ')
                .replaceAll("\\s+", " ");
        return collapsed.isBlank() ? null : collapsed.toLowerCase(Locale.ROOT);
    }

    private String presentMistakeLabel(String rawLabel) {
        if (rawLabel == null) {
            return "";
        }
        String collapsed = rawLabel.trim()
                .replace('_', ' ')
                .replaceAll("\\s+", " ");
        if (collapsed.isBlank()) {
            return "";
        }
        if (collapsed.equals(collapsed.toLowerCase(Locale.ROOT))) {
            return Character.toUpperCase(collapsed.charAt(0)) + collapsed.substring(1);
        }
        return collapsed;
    }

    private List<TopMistakeTrendView> buildTopMistakeViews(
            MistakeCountSnapshot baselineSnapshot,
            MistakeCountSnapshot recent10Snapshot,
            MistakeCountSnapshot previous10Snapshot
    ) {
        return baselineSnapshot.counts().entrySet().stream()
                .sorted(Comparator
                        .comparingLong((Map.Entry<String, Long> entry) -> entry.getValue())
                        .reversed()
                        .thenComparing(entry -> baselineSnapshot.labels().getOrDefault(entry.getKey(), entry.getKey()), String.CASE_INSENSITIVE_ORDER))
                .limit(5)
                .map(entry -> {
                    long recentCount = recent10Snapshot.counts().getOrDefault(entry.getKey(), 0L);
                    long previousCount = previous10Snapshot.counts().getOrDefault(entry.getKey(), 0L);
                    TrendDirection trendDirection = resolveTrendDirection(recentCount, previousCount);
                    return new TopMistakeTrendView(
                            baselineSnapshot.labels().getOrDefault(entry.getKey(), entry.getKey()),
                            entry.getValue(),
                            trendDirection.label(),
                            trendDirection.cssClass()
                    );
                })
                .toList();
    }

    private List<DistributionPoint> buildDistributionPoints(MistakeCountSnapshot snapshot) {
        long totalCount = snapshot.counts().values().stream()
                .mapToLong(Long::longValue)
                .sum();
        long maxCount = snapshot.counts().values().stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);

        return snapshot.counts().entrySet().stream()
                .sorted(Comparator
                        .comparingLong((Map.Entry<String, Long> entry) -> entry.getValue())
                        .reversed()
                        .thenComparing(entry -> snapshot.labels().getOrDefault(entry.getKey(), entry.getKey()), String.CASE_INSENSITIVE_ORDER))
                .limit(5)
                .map(entry -> new DistributionPoint(
                        snapshot.labels().getOrDefault(entry.getKey(), entry.getKey()),
                        entry.getValue(),
                        toPercent(entry.getValue(), totalCount),
                        toPercent(entry.getValue(), maxCount)
                ))
                .toList();
    }

    private String buildQuickInsight(
            MistakeCountSnapshot recent30Snapshot,
            MistakeCountSnapshot recent10Snapshot,
            MistakeCountSnapshot previous10Snapshot
    ) {
        if (recent30Snapshot.counts().isEmpty()) {
            return "Tag mistakes on trades or use AI reviews to unlock behavior trends.";
        }
        if (recent10Snapshot.counts().isEmpty() && previous10Snapshot.counts().isEmpty()) {
            return "Not enough recent trades to detect a mistake trend yet.";
        }

        TrendCandidate bestCandidate = recent30Snapshot.counts().entrySet().stream()
                .map(entry -> {
                    long recentCount = recent10Snapshot.counts().getOrDefault(entry.getKey(), 0L);
                    long previousCount = previous10Snapshot.counts().getOrDefault(entry.getKey(), 0L);
                    long delta = recentCount - previousCount;
                    return new TrendCandidate(
                            recent30Snapshot.labels().getOrDefault(entry.getKey(), entry.getKey()),
                            delta
                    );
                })
                .sorted(Comparator
                        .comparingLong(TrendCandidate::priority)
                        .reversed()
                        .thenComparing(candidate -> candidate.label(), String.CASE_INSENSITIVE_ORDER))
                .findFirst()
                .orElse(null);

        if (bestCandidate == null || bestCandidate.delta() == 0) {
            return "Mistake frequency stayed broadly flat in the last 10 trades.";
        }

        if (bestCandidate.delta() > 0) {
            return bestCandidate.label() + " frequency increased in the last 10 trades";
        }
        return bestCandidate.label() + " frequency decreased in the last 10 trades";
    }

    private TrendDirection resolveTrendDirection(long recentCount, long previousCount) {
        if (recentCount > previousCount) {
            return TrendDirection.INCREASING;
        }
        if (recentCount < previousCount) {
            return TrendDirection.DECREASING;
        }
        return TrendDirection.STABLE;
    }

    private LocalDateTime resolveTradeTimestamp(Trade trade) {
        if (trade == null) {
            return null;
        }
        if (trade.getEntryTime() != null) {
            return trade.getEntryTime();
        }
        if (trade.getTradeDate() != null) {
            return trade.getTradeDate();
        }
        return trade.getCreatedAt();
    }

    private long toPercent(long value, long total) {
        if (value <= 0 || total <= 0) {
            return 0L;
        }
        return Math.round((value * 100.0) / total);
    }

    public record MistakeTrendReport(
            String mostFrequentMistakeName,
            long mostFrequentMistakeUsage,
            long mostFrequentUsagePercent,
            List<TopMistakeTrendView> topMistakes,
            List<DistributionPoint> distributionPoints,
            String quickInsight
    ) {
    }

    public record TopMistakeTrendView(
            String name,
            long usageCount,
            String trendDirection,
            String trendClass
    ) {
    }

    public record DistributionPoint(
            String label,
            long usageCount,
            long usagePercent,
            long widthPercent
    ) {
    }

    private record MistakeCountSnapshot(
            Map<String, Long> counts,
            Map<String, String> labels
    ) {
        private static MistakeCountSnapshot empty() {
            return new MistakeCountSnapshot(Map.of(), Map.of());
        }
    }

    private record TrendCandidate(String label, long delta) {
        private long priority() {
            if (delta > 0) {
                return (Math.abs(delta) * 10) + 1;
            }
            return Math.abs(delta);
        }
    }

    private enum TrendDirection {
        INCREASING("Increasing", " is-up"),
        DECREASING("Decreasing", " is-down"),
        STABLE("Stable", " is-flat");

        private final String label;
        private final String cssClass;

        TrendDirection(String label, String cssClass) {
            this.label = label;
            this.cssClass = cssClass;
        }

        public String label() {
            return label;
        }

        public String cssClass() {
            return cssClass;
        }
    }
}
