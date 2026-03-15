package com.example.demo.service;

import org.springframework.stereotype.Service;

@Service
public class ResultResolver {

    public TradingViewChartImportService.TradeChartAnalysis resolve(TradingViewChartImportService.TradeChartAnalysis analysis) {
        if (analysis == null) {
            return null;
        }

        String result = normalizeResult(analysis.result());
        String exitReason = normalizeExitReason(analysis.exitReason());
        Double exitPrice = analysis.exitPrice();

        if (result == null) {
            result = inferResultFromExitReason(exitReason);
        }

        if (result == null) {
            result = inferResultFromExitPrice(analysis, exitPrice);
        }

        if ("UNKNOWN".equals(result)) {
            return analysis.withResolvedOutcome("UNKNOWN", null, null);
        }

        if ("WIN".equals(result)) {
            if (exitPrice == null && analysis.takeProfit() != null) {
                exitPrice = analysis.takeProfit();
            }
            if (exitReason == null) {
                exitReason = "TP_HIT";
            }
        } else if ("LOSS".equals(result)) {
            if (exitPrice == null && analysis.stopLoss() != null) {
                exitPrice = analysis.stopLoss();
            }
            if (exitReason == null) {
                exitReason = "SL_HIT";
            }
        } else if ("BREAKEVEN".equals(result)) {
            if (exitPrice == null && analysis.entryPrice() != null) {
                exitPrice = analysis.entryPrice();
            }
            if (exitReason == null) {
                exitReason = "BE_HIT";
            }
        } else if ("PARTIAL_WIN".equals(result)) {
            if (exitPrice == null && analysis.entryPrice() != null) {
                exitPrice = analysis.entryPrice();
            }
            if (exitReason == null || "BE_HIT".equals(exitReason)) {
                exitReason = "PARTIAL_TP_THEN_BE";
            }
        } else {
            return analysis.withResolvedOutcome(null, null, null);
        }

        return analysis.withResolvedOutcome(result, exitPrice, exitReason);
    }

    private String inferResultFromExitReason(String exitReason) {
        if (exitReason == null) {
            return null;
        }

        return switch (exitReason) {
            case "TP_HIT" -> "WIN";
            case "SL_HIT" -> "LOSS";
            case "BE_HIT" -> "BREAKEVEN";
            case "PARTIAL_TP_THEN_BE" -> "PARTIAL_WIN";
            case "UNKNOWN" -> "UNKNOWN";
            default -> null;
        };
    }

    private String inferResultFromExitPrice(TradingViewChartImportService.TradeChartAnalysis analysis, Double exitPrice) {
        if (exitPrice == null) {
            return null;
        }
        if (approximatelyEqual(exitPrice, analysis.takeProfit())) {
            return "WIN";
        }
        if (approximatelyEqual(exitPrice, analysis.stopLoss())) {
            return "LOSS";
        }
        if (approximatelyEqual(exitPrice, analysis.entryPrice())) {
            return "BREAKEVEN";
        }
        return null;
    }

    private boolean approximatelyEqual(Double left, Double right) {
        if (left == null || right == null) {
            return false;
        }
        double tolerance = Math.max(0.00001d, Math.max(Math.abs(left), Math.abs(right)) * 0.000001d);
        return Math.abs(left - right) <= tolerance;
    }

    private String normalizeResult(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toUpperCase()) {
            case "WIN", "LOSS", "BREAKEVEN", "PARTIAL_WIN", "UNKNOWN" -> value.trim().toUpperCase();
            case "BE" -> "BREAKEVEN";
            default -> null;
        };
    }

    private String normalizeExitReason(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toUpperCase()) {
            case "TP_HIT", "SL_HIT", "BE_HIT", "PARTIAL_TP_THEN_BE", "MANUAL_EXIT", "UNKNOWN" -> value.trim().toUpperCase();
            default -> null;
        };
    }
}
