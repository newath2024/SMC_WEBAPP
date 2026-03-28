package com.tradejournal.service;

import com.tradejournal.ai.integration.TradingViewChartImportService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void rejectsSvgScreenshots() {
        TradingViewChartImportService service = new TradingViewChartImportService(
                "test-key",
                "https://api.openai.com/v1",
                "gpt-4.1",
                60
        );
        MockMultipartFile svg = new MockMultipartFile(
                "file",
                "chart.svg",
                "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes()
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.analyzeChart(svg));
        assertTrue(error.getMessage().contains("Only PNG, JPG, WEBP, or GIF"));
    }
}
