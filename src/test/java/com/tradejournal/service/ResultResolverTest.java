package com.tradejournal.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResultResolverTest {

    private final ResultResolver resolver = new ResultResolver();

    @Test
    void infersTpHitExitPriceFromWinResult() {
        TradingViewChartImportService.TradeChartAnalysis analysis = new TradingViewChartImportService.TradeChartAnalysis(
                "BTCUSD", "SELL", "H1", "M3", "M15",
                70573.9, 70827.7, 69804.3,
                null, null, null,
                8, 120,
                "WIN", null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, "high"
        );

        TradingViewChartImportService.TradeChartAnalysis resolved = resolver.resolve(analysis);

        assertEquals("WIN", resolved.result());
        assertEquals(69804.3, resolved.exitPrice());
        assertEquals("TP_HIT", resolved.exitReason());
    }
}
