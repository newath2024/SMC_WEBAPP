package com.tradejournal.domain;

import com.tradejournal.trade.domain.Trade;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeTimeFallbackTest {

    @Test
    void holdingMinutesUsesExactTimesBeforeEstimatedValue() {
        Trade trade = new Trade();
        trade.setEntryTime(LocalDateTime.of(2026, 3, 15, 10, 0));
        trade.setExitTime(LocalDateTime.of(2026, 3, 15, 12, 15));
        trade.setEstimatedHoldingMinutes(60);

        assertEquals(135L, trade.getHoldingMinutes());
        assertEquals("Exact", trade.getHoldingMinutesSourceLabel());
    }

    @Test
    void sessionFallsBackToAiGuessWhenExactEntryTimeIsMissing() {
        Trade trade = new Trade();
        trade.setSessionGuess("New York");
        trade.setSessionConfidence("medium");

        assertEquals("NEW_YORK", trade.getSession());
        assertEquals("Estimated", trade.getSessionSourceLabel());
    }
}
