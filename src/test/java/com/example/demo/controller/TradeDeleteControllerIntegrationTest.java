package com.example.demo.controller;

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

import static com.example.demo.controller.AuthController.SESSION_USER_ID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("local")
class TradeDeleteControllerIntegrationTest {

    private static final String USER_ID = "02681084-a5b8-43c3-abf3-2c774a89dc0a";
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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
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
}
