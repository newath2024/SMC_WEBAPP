package com.example.demo.service;

import com.example.demo.entity.Trade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class TradeDeleteIntegrationTest {

    private static final String USER_ID = "02681084-a5b8-43c3-abf3-2c774a89dc0a";
    private static final Path SOURCE_DB = Path.of("data", "trading_journal.db");
    private static final Path TEST_DIR = Path.of("target", "test-data");
    private static final Path TEST_DB = TEST_DIR.resolve("trade-delete-repro.db");

    static {
        try {
            Files.createDirectories(TEST_DIR);
            Files.deleteIfExists(TEST_DB);
            Files.deleteIfExists(Path.of(TEST_DB + "-wal"));
            Files.deleteIfExists(Path.of(TEST_DB + "-shm"));
            Files.copy(SOURCE_DB, TEST_DB, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:sqlite:" + TEST_DB.toAbsolutePath().toString().replace('\\', '/')
                        + "?busy_timeout=10000&journal_mode=WAL&synchronous=NORMAL");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private TradeService tradeService;

    @Autowired
    private TradeImageService tradeImageService;

    @Test
    void deleteForUserIdsDeletesTradesFromSQLiteDatabase() {
        List<String> tradeIds = tradeService.findAllByUser(USER_ID).stream()
                .map(Trade::getId)
                .toList();

        assertFalse(tradeIds.isEmpty());

        assertDoesNotThrow(() -> {
            tradeImageService.deleteByTradeIds(tradeIds);
            assertEquals(tradeIds.size(), tradeService.deleteForUserIds(tradeIds, USER_ID));
            assertTrue(tradeService.findAllByUser(USER_ID).isEmpty());
        });
    }
}
