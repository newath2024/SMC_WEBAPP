package com.example.demo.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HoldingTimeResolverTest {

    private final HoldingTimeResolver resolver = new HoldingTimeResolver();

    @Test
    void recalculatesHoldingMinutesFromResultTimeframe() {
        TradingViewChartImportService.TradeChartAnalysis analysis = new TradingViewChartImportService.TradeChartAnalysis(
                "BTCUSD", "SELL", "H1", "M3", "M15",
                70573.9, 70827.7, 69804.3,
                null, null, null,
                8, 60,
                null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, "high"
        );

        TradingViewChartImportService.TradeChartAnalysis resolved = resolver.resolve(analysis);

        assertEquals(8, resolved.estimatedResultCandlesHeld());
        assertEquals(120, resolved.estimatedHoldingMinutes());
    }
}
