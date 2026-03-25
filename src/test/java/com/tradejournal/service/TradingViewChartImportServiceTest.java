package com.tradejournal.service;

import com.tradejournal.ai.integration.TradingViewChartImportService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TradingViewChartImportServiceTest {

    @Test
    void parsesTradingViewUsdLabelsWithThousandsSeparators() {
        assertEquals(73388.3, TradingViewChartImportService.parseVisiblePrice("73,388.3"));
        assertEquals(74216.6, TradingViewChartImportService.parseVisiblePrice("74,216.6"));
        assertEquals(70874.5, TradingViewChartImportService.parseVisiblePrice("70,874.5"));
    }

    @Test
    void parsesEuropeanStyleVisibleLabels() {
        assertEquals(74216.6, TradingViewChartImportService.parseVisiblePrice("74.216,6"));
        assertEquals(71135.0, TradingViewChartImportService.parseVisiblePrice("71.135,0"));
    }

    @Test
    void stripsExtraDecorationsAroundPriceLabels() {
        assertEquals(73388.3, TradingViewChartImportService.parseVisiblePrice("Entry: 73,388.3 USD"));
        assertEquals(74216.6, TradingViewChartImportService.parseVisiblePrice("SL 74,216.6"));
        assertEquals(70874.5, TradingViewChartImportService.parseVisiblePrice("TP 70,874.5"));
    }

    @Test
    void returnsNullForMissingOrInvalidPrices() {
        assertNull(TradingViewChartImportService.parseVisiblePrice(null));
        assertNull(TradingViewChartImportService.parseVisiblePrice(""));
        assertNull(TradingViewChartImportService.parseVisiblePrice("not-visible"));
    }
}
