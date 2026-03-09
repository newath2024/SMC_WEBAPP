package com.example.demo.service;

import com.example.demo.entity.Trade;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnalyticsService {

    private final TradeService tradeService;

    public AnalyticsService(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    public AnalyticsReport buildReportForUser(String userId) {
        List<Trade> trades = tradeService.findAllByUser(userId);
        TradeOverview overview = buildOverview(trades);

        List<BreakdownRow> bySetup = buildBreakdown(trades, Trade::getSetupName);
        List<BreakdownRow> bySession = buildBreakdown(trades, Trade::getSession);
        List<BreakdownRow> bySymbol = buildBreakdown(trades, Trade::getSymbol);

        return new AnalyticsReport(overview, bySetup, bySession, bySymbol);
    }

    public TradeOverview buildOverviewForUser(String userId) {
        List<Trade> trades = tradeService.findAllByUser(userId);
        return buildOverview(trades);
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
        private final List<BreakdownRow> bySetup;
        private final List<BreakdownRow> bySession;
        private final List<BreakdownRow> bySymbol;

        public AnalyticsReport(
                TradeOverview overview,
                List<BreakdownRow> bySetup,
                List<BreakdownRow> bySession,
                List<BreakdownRow> bySymbol
        ) {
            this.overview = overview;
            this.bySetup = bySetup;
            this.bySession = bySession;
            this.bySymbol = bySymbol;
        }

        public TradeOverview getOverview() {
            return overview;
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
