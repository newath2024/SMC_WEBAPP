package com.example.demo.service;

import com.example.demo.entity.MistakeTag;
import com.example.demo.entity.Trade;
import com.example.demo.entity.TradeImage;
import com.example.demo.entity.TradeMistakeTag;
import com.example.demo.entity.TradeReview;
import com.example.demo.entity.User;
import com.example.demo.repository.MistakeTagRepository;
import com.example.demo.repository.TradeImageRepository;
import com.example.demo.repository.TradeMistakeTagRepository;
import com.example.demo.repository.TradeReviewRepository;
import com.example.demo.repository.TradeRepository;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class TradeDeleteIntegrationTest {

    private static final String USER_ID = "02681084-a5b8-43c3-abf3-2c774a89dc0a";
    private static final String TRADE_ID_1 = "7ad3d190-e6b2-40a7-8684-1f64eda0378e";
    private static final String TRADE_ID_2 = "29a56d8e-1fff-4b8e-a7ef-e1f1858b0220";
    private static final String REVIEW_ID_1 = "review-0001-0000-0000-000000000001";
    private static final String REVIEW_ID_2 = "review-0002-0000-0000-000000000002";
    private static final String TAG_ID = "mistake-0001-0000-0000-000000000001";
    private static final String LINK_ID_1 = "link-0001-0000-0000-000000000001";
    private static final String LINK_ID_2 = "link-0002-0000-0000-000000000002";
    private static final String IMAGE_ID_1 = "image-0001-0000-0000-000000000001";
    private static final String IMAGE_ID_2 = "image-0002-0000-0000-000000000002";
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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private TradeReviewRepository tradeReviewRepository;

    @Autowired
    private TradeMistakeTagRepository tradeMistakeTagRepository;

    @Autowired
    private TradeImageRepository tradeImageRepository;

    @Autowired
    private MistakeTagRepository mistakeTagRepository;

    @BeforeEach
    void seedData() {
        User user = userRepository.findById(USER_ID).orElseGet(() -> {
            User created = new User();
            created.setId(USER_ID);
            created.setUsername("delete-test-user");
            created.setEmail("delete-test-user@example.com");
            created.setPasswordHash("noop");
            created.setRole("USER");
            return userRepository.save(created);
        });

        MistakeTag mistakeTag = mistakeTagRepository.findById(TAG_ID).orElseGet(() -> {
            MistakeTag created = new MistakeTag();
            created.setId(TAG_ID);
            created.setCode("DELETE_TEST_TAG");
            created.setName("Delete Test Tag");
            return mistakeTagRepository.save(created);
        });

        Trade tradeOne = ensureTrade(user, TRADE_ID_1, "BTCUSD", LocalDateTime.of(2026, 3, 12, 15, 47));
        Trade tradeTwo = ensureTrade(user, TRADE_ID_2, "XAUUSD", LocalDateTime.of(2026, 3, 6, 12, 13));

        ensureReview(tradeOne, REVIEW_ID_1);
        ensureReview(tradeTwo, REVIEW_ID_2);
        ensureMistakeLink(tradeOne, mistakeTag, LINK_ID_1);
        ensureMistakeLink(tradeTwo, mistakeTag, LINK_ID_2);
        ensureImage(tradeOne, IMAGE_ID_1, "/uploads/trade-images/delete-test-1.png");
        ensureImage(tradeTwo, IMAGE_ID_2, "/uploads/trade-images/delete-test-2.png");
    }

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

    private Trade ensureTrade(User user, String tradeId, String symbol, LocalDateTime entryTime) {
        return tradeRepository.findById(tradeId).orElseGet(() -> {
            Trade trade = new Trade();
            trade.setId(tradeId);
            trade.setUser(user);
            trade.setAccountLabel("Primary");
            trade.setSymbol(symbol);
            trade.setDirection("BUY");
            trade.setHtf("H1");
            trade.setLtf("M15");
            trade.setEntryPrice(100.0);
            trade.setStopLoss(95.0);
            trade.setTakeProfit(110.0);
            trade.setExitPrice(108.0);
            trade.setPositionSize(1.0);
            trade.setResult("WIN");
            trade.setPnl(8.0);
            trade.setEntryTime(entryTime);
            trade.setTradeDate(entryTime);
            return tradeRepository.save(trade);
        });
    }

    private void ensureReview(Trade trade, String reviewId) {
        if (tradeReviewRepository.findByTradeId(trade.getId()).isPresent()) {
            return;
        }
        TradeReview review = new TradeReview();
        review.setId(reviewId);
        review.setTrade(trade);
        review.setQualityScore(80);
        tradeReviewRepository.save(review);
    }

    private void ensureMistakeLink(Trade trade, MistakeTag mistakeTag, String linkId) {
        if (tradeMistakeTagRepository.existsByTradeIdAndMistakeTagId(trade.getId(), mistakeTag.getId())) {
            return;
        }
        TradeMistakeTag link = new TradeMistakeTag();
        link.setId(linkId);
        link.setTrade(trade);
        link.setMistakeTag(mistakeTag);
        tradeMistakeTagRepository.save(link);
    }

    private void ensureImage(Trade trade, String imageId, String imageUrl) {
        if (tradeImageRepository.findById(imageId).isPresent()) {
            return;
        }
        TradeImage image = new TradeImage();
        image.setId(imageId);
        image.setTrade(trade);
        image.setImageType("SETUP");
        image.setImageUrl(imageUrl);
        tradeImageRepository.save(image);
    }
}
