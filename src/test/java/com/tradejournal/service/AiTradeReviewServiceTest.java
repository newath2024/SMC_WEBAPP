package com.tradejournal.service;

import com.tradejournal.ai.service.AiTradeReviewService;
import com.tradejournal.trade.repository.TradeReviewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class AiTradeReviewServiceTest {

    @Test
    void resolveImagePathRejectsTraversalSegments() {
        AiTradeReviewService service = new AiTradeReviewService(
                mock(TradeReviewRepository.class),
                "test-key",
                "https://api.openai.com/v1",
                "gpt-4.1-mini",
                30
        );

        Path resolved = ReflectionTestUtils.invokeMethod(
                service,
                "resolveImagePath",
                "/uploads/trade-images/../outside.txt"
        );

        assertNull(resolved);
    }

    @Test
    void resolveImagePathKeepsTradeImagesInsideStorageRoot() {
        AiTradeReviewService service = new AiTradeReviewService(
                mock(TradeReviewRepository.class),
                "test-key",
                "https://api.openai.com/v1",
                "gpt-4.1-mini",
                30
        );

        Path resolved = ReflectionTestUtils.invokeMethod(
                service,
                "resolveImagePath",
                "/uploads/trade-images/chart.png"
        );

        assertEquals(
                Path.of("data", "uploads", "trade-images").toAbsolutePath().normalize().resolve("chart.png"),
                resolved
        );
    }
}
