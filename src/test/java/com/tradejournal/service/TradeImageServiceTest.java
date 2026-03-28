package com.tradejournal.service;

import com.tradejournal.trade.domain.Trade;
import com.tradejournal.trade.domain.TradeImage;
import com.tradejournal.trade.repository.TradeImageRepository;
import com.tradejournal.trade.service.TradeImageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TradeImageServiceTest {

    @Mock
    private TradeImageRepository tradeImageRepository;

    @Test
    void saveSetupImagesRejectsSvgUploads() {
        TradeImageService tradeImageService = new TradeImageService(tradeImageRepository);
        Trade trade = new Trade();
        trade.setId("trade-1");
        MockMultipartFile svg = new MockMultipartFile(
                "files",
                "chart.svg",
                "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes()
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> tradeImageService.saveSetupImages(trade, new MockMultipartFile[]{svg}, "BEFORE")
        );

        assertTrue(error.getMessage().contains("Only PNG, JPG, WEBP, or GIF"));
        verify(tradeImageRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deleteByTradeIdIgnoresImageUrlsOutsideStorageDir() throws IOException {
        TradeImageService tradeImageService = new TradeImageService(tradeImageRepository);
        TradeImage image = new TradeImage();
        image.setImageUrl("/uploads/trade-images/../path-traversal-test.txt");

        Path outsideFile = Path.of("data", "uploads", "path-traversal-test.txt").toAbsolutePath().normalize();
        Files.createDirectories(outsideFile.getParent());
        Files.writeString(outsideFile, "keep");

        when(tradeImageRepository.findByTradeIdOrderByCreatedAtAsc("trade-1")).thenReturn(List.of(image));

        try {
            tradeImageService.deleteByTradeId("trade-1");
            assertTrue(Files.exists(outsideFile));
            verify(tradeImageRepository).deleteByTradeId("trade-1");
        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }
}
