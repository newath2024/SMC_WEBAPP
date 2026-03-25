package com.tradejournal.service;

import com.tradejournal.ai.integration.TradingViewChartImportService;
import com.tradejournal.setup.domain.Setup;
import com.tradejournal.setup.service.SetupMatcher;
import com.tradejournal.setup.service.SetupService;
import com.tradejournal.auth.domain.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SetupMatcherTest {

    @Test
    void matchesExistingSetupFromAiSignals() {
        SetupService setupService = mock(SetupService.class);
        SetupMatcher matcher = new SetupMatcher(setupService);

        Setup setup = new Setup();
        setup.setId("setup-1");
        setup.setName("OB + FVG Reversal");
        setup.setDescription("Order block rejection with fair value gap and MSS confirmation.");

        User user = new User();
        user.setId("user-1");

        when(setupService.findActiveByUser("user-1")).thenReturn(List.of(setup));

        TradingViewChartImportService.TradeChartAnalysis analysis = new TradingViewChartImportService.TradeChartAnalysis(
                "BTCUSD", "SELL", "H1", "M3", "M15",
                70573.9, 70827.7, 69804.3,
                null, null, null,
                8, 120,
                "WIN", 69804.3, "TP_HIT",
                "New York", "medium", "bearish",
                "premium ob with mss", "fvg entry after sweep", "OB + FVG Reversal", "Short idea",
                null, null, null, null, null, "high"
        );

        TradingViewChartImportService.TradeChartAnalysis resolved = matcher.resolve(analysis, user);

        assertEquals("setup-1", resolved.matchedSetupId());
        assertEquals("OB + FVG Reversal", resolved.matchedSetupName());
        assertNull(resolved.newSetupSuggestedName());
    }
}
