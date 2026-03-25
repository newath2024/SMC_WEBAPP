package com.tradejournal.controller;

import com.tradejournal.entity.MistakeTag;
import com.tradejournal.entity.Trade;
import com.tradejournal.entity.TradeImage;
import com.tradejournal.entity.TradeMistakeTag;
import com.tradejournal.entity.TradeReview;
import com.tradejournal.entity.User;
import com.tradejournal.repository.MistakeTagRepository;
import com.tradejournal.repository.TradeImageRepository;
import com.tradejournal.repository.TradeMistakeTagRepository;
import com.tradejournal.repository.TradeRepository;
import com.tradejournal.repository.TradeReviewRepository;
import com.tradejournal.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;

import static com.tradejournal.controller.AuthController.SESSION_USER_ID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
class TradeDeleteControllerIntegrationTest {

    private static final String USER_ID = "02681084-a5b8-43c3-abf3-2c774a89dc0a";
    private static final String TRADE_ID_1 = "7ad3d190-e6b2-40a7-8684-1f64eda0378e";
    private static final String TRADE_ID_2 = "29a56d8e-1fff-4b8e-a7ef-e1f1858b0220";
    private static final String REVIEW_ID_1 = "controller-review-0001-000000000001";
    private static final String REVIEW_ID_2 = "controller-review-0002-000000000002";
    private static final String TAG_ID = "controller-mistake-0001-0000000001";
    private static final String LINK_ID_1 = "controller-link-0001-000000000001";
    private static final String LINK_ID_2 = "controller-link-0002-000000000002";
    private static final String IMAGE_ID_1 = "controller-image-0001-00000000001";
    private static final String IMAGE_ID_2 = "controller-image-0002-00000000002";
    private static final Path SOURCE_DB = Path.of("data", "trading_journal.db");
    private static final Path TEST_DIR = Path.of("target", "test-data");
    private static final Path TEST_DB = TEST_DIR.resolve("trade-delete-controller-repro.db");

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
    private WebApplicationContext webApplicationContext;

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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        seedData();
    }

    @Test
    void deleteSelectedReturnsJsonSuccessForAjaxRequest() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SESSION_USER_ID, USER_ID);

        mockMvc.perform(post("/trades/delete-selected")
                        .session(session)
                        .header("Accept", "application/json")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .param("tradeIds", "7ad3d190-e6b2-40a7-8684-1f64eda0378e")
                        .param("tradeIds", "29a56d8e-1fff-4b8e-a7ef-e1f1858b0220"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.deletedCount").value(2))
                .andExpect(jsonPath("$.deletedTradeIds.length()").value(2));
    }

    private void seedData() {
        User user = userRepository.findById(USER_ID).orElseGet(() -> {
            User created = new User();
            created.setId(USER_ID);
            created.setUsername("controller-delete-test-user");
            created.setEmail("controller-delete-test-user@example.com");
            created.setPasswordHash("noop");
            created.setRole("USER");
            return userRepository.save(created);
        });

        MistakeTag mistakeTag = mistakeTagRepository.findById(TAG_ID).orElseGet(() -> {
            MistakeTag created = new MistakeTag();
            created.setId(TAG_ID);
            created.setCode("CONTROLLER_DELETE_TEST");
            created.setName("Controller Delete Test");
            return mistakeTagRepository.save(created);
        });

        Trade tradeOne = ensureTrade(user, TRADE_ID_1, "BTCUSD", LocalDateTime.of(2026, 3, 12, 15, 47));
        Trade tradeTwo = ensureTrade(user, TRADE_ID_2, "XAUUSD", LocalDateTime.of(2026, 3, 6, 12, 13));

        ensureReview(tradeOne, REVIEW_ID_1);
        ensureReview(tradeTwo, REVIEW_ID_2);
        ensureMistakeLink(tradeOne, mistakeTag, LINK_ID_1);
        ensureMistakeLink(tradeTwo, mistakeTag, LINK_ID_2);
        ensureImage(tradeOne, IMAGE_ID_1, "/uploads/trade-images/controller-delete-test-1.png");
        ensureImage(tradeTwo, IMAGE_ID_2, "/uploads/trade-images/controller-delete-test-2.png");
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
