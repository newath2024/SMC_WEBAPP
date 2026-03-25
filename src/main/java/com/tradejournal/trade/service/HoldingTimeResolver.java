package com.tradejournal.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class HoldingTimeResolver {

    public TradingViewChartImportService.TradeChartAnalysis resolve(TradingViewChartImportService.TradeChartAnalysis analysis) {
        if (analysis == null) {
            return null;
        }

        Integer resultTimeframeMinutes = timeframeToMinutes(analysis.timeframeResult());
        if (StringUtils.hasText(analysis.timeframeResult())) {
            if (resultTimeframeMinutes != null && analysis.estimatedResultCandlesHeld() != null) {
                return analysis.withHoldingTime(
                        analysis.timeframeResult(),
                        analysis.estimatedResultCandlesHeld(),
                        analysis.estimatedResultCandlesHeld() * resultTimeframeMinutes
                );
            }

            // A result screenshot exists, but the duration estimate is not reliable enough.
            return analysis.withHoldingTime(
                    analysis.timeframeResult(),
                    analysis.estimatedResultCandlesHeld(),
                    null
            );
        }

        return analysis;
    }

    static Integer timeframeToMinutes(String timeframe) {
        if (!StringUtils.hasText(timeframe)) {
            return null;
        }

        return switch (timeframe.trim().toUpperCase()) {
            case "M1" -> 1;
            case "M3" -> 3;
            case "M5" -> 5;
            case "M15" -> 15;
            case "M30" -> 30;
            case "H1" -> 60;
            case "H4" -> 240;
            default -> null;
        };
    }
}
