package com.example.demo.controller;

import com.example.demo.entity.PlanType;
import com.example.demo.entity.Trade;
import com.example.demo.entity.User;
import com.example.demo.repository.TradeRepository;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.List;

import static com.example.demo.controller.AuthController.SESSION_USER_ID;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
class DashboardPeriodIntegrationTest {

    private static final String USER_ID = "9d9698bf-44c3-43dc-b197-5ee3f6f52531";
    private static final String TRADE_ID_CURRENT = "648c6983-a481-4b3d-b202-0bdad6aebd3e";
    private static final String TRADE_ID_PREVIOUS = "ff7d6ffc-63b0-4d18-9288-218cbf2eb7af";
    private static final Path SOURCE_DB = Path.of("data", "trading_journal.db");
    private static final Path TEST_DIR = Path.of("target", "test-data");
    private static final Path TEST_DB = TEST_DIR.resolve("dashboard-period-repro.db");

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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        seedData();
    }

    @Test
    void dashboardPeriodsRenderComparisonCards() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SESSION_USER_ID, USER_ID);

        for (String period : List.of("7D", "30D", "90D")) {
            mockMvc.perform(get("/dashboard")
                            .session(session)
                            .param("period", period))
                    .andExpect(status().isOk())
                    .andExpect(view().name("dashboard"))
                    .andExpect(content().string(containsString("vs last period")));
        }
    }

    private void seedData() {
        User user = userRepository.findById(USER_ID).orElseGet(() -> {
            User created = new User();
            created.setId(USER_ID);
            created.setUsername("dashboard-period-user");
            created.setEmail("dashboard-period-user@example.com");
            created.setPasswordHash("noop");
            created.setRole("USER");
            created.setPlanType(PlanType.PRO);
            return userRepository.save(created);
        });

        ensureTrade(
                user,
                TRADE_ID_CURRENT,
                LocalDateTime.now().minusDays(1).withHour(9).withMinute(30).withSecond(0).withNano(0),
                "BTCUSD",
                "WIN",
                125.50,
                2.40
        );
        ensureTrade(
                user,
                TRADE_ID_PREVIOUS,
                LocalDateTime.now().minusDays(10).withHour(14).withMinute(15).withSecond(0).withNano(0),
                "XAUUSD",
                "LOSS",
                -48.25,
                -0.95
        );
    }

    private void ensureTrade(
            User user,
            String tradeId,
            LocalDateTime entryTime,
            String symbol,
            String result,
            double pnl,
            double rMultiple
    ) {
        tradeRepository.findById(tradeId).orElseGet(() -> {
            Trade trade = new Trade();
            trade.setId(tradeId);
            trade.setUser(user);
            trade.setAccountLabel("Primary");
            trade.setTradeDate(entryTime);
            trade.setEntryTime(entryTime);
            trade.setExitTime(entryTime.plusHours(2));
            trade.setSymbol(symbol);
            trade.setDirection("BUY");
            trade.setHtf("H1");
            trade.setLtf("M15");
            trade.setEntryPrice(100.0);
            trade.setStopLoss(95.0);
            trade.setTakeProfit(110.0);
            trade.setExitPrice(108.0);
            trade.setPositionSize(1.0);
            trade.setResult(result);
            trade.setPnl(pnl);
            trade.setRMultiple(rMultiple);
            trade.setSession("LONDON");
            return tradeRepository.save(trade);
        });
    }
}
