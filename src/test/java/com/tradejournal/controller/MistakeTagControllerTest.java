package com.tradejournal.controller;

import com.tradejournal.auth.domain.User;
import com.tradejournal.auth.service.UserService;
import com.tradejournal.mistake.controller.MistakeTagController;
import com.tradejournal.mistake.domain.MistakeTag;
import com.tradejournal.mistake.service.MistakeAnalyticsService;
import com.tradejournal.mistake.service.MistakeTagService;
import com.tradejournal.trade.repository.TradeMistakeTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.RedirectView;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class MistakeTagControllerTest {

    private MistakeTagService mistakeTagService;
    private MistakeAnalyticsService mistakeAnalyticsService;
    private TradeMistakeTagRepository tradeMistakeTagRepository;
    private UserService userService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mistakeTagService = mock(MistakeTagService.class);
        mistakeAnalyticsService = mock(MistakeAnalyticsService.class);
        tradeMistakeTagRepository = mock(TradeMistakeTagRepository.class);
        userService = mock(UserService.class);

        MistakeTagController controller = new MistakeTagController(
                mistakeTagService,
                mistakeAnalyticsService,
                tradeMistakeTagRepository,
                userService
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setViewResolvers((viewName, locale) -> {
                    if (viewName.startsWith("redirect:")) {
                        return new RedirectView(viewName.substring("redirect:".length()));
                    }
                    return (model, request, response) -> { };
                })
                .build();
    }

    @Test
    void listUsesGlobalMetricsForAdminView() throws Exception {
        User admin = new User();
        admin.setId("admin-1");
        admin.setRole("ADMIN");

        MistakeTag globalTag = new MistakeTag();
        globalTag.setId("mistake-1");
        globalTag.setCode("FOMO");
        globalTag.setName("FOMO");
        globalTag.setActive(true);

        when(userService.getCurrentUser(any())).thenReturn(admin);
        when(userService.isAdmin(admin)).thenReturn(true);
        when(mistakeTagService.findAllForAdmin()).thenReturn(List.of(globalTag));
        when(tradeMistakeTagRepository.countUsageByMistakeTag()).thenReturn(List.of(usage("mistake-1", 7)));
        when(tradeMistakeTagRepository.summarizeBySession()).thenReturn(List.of(session("LONDON", 4)));
        when(tradeMistakeTagRepository.summarizeBySymbol()).thenReturn(List.of(symbol("XAUUSD", 3)));
        when(tradeMistakeTagRepository.findRecentMistakes()).thenReturn(List.of(recent("trade-1", "FOMO", "LONDON", "XAUUSD")));
        when(mistakeAnalyticsService.buildTrendReportForAdmin()).thenReturn(emptyTrendReport());

        MvcResult result = mockMvc.perform(get("/mistakes").session(new MockHttpSession()))
                .andExpect(status().isOk())
                .andExpect(view().name("mistakes"))
                .andReturn();

        Object mistakesModel = result.getModelAndView().getModel().get("mistakes");
        assertInstanceOf(List.class, mistakesModel);
        @SuppressWarnings("unchecked")
        List<MistakeTagController.MistakeRowView> rows = (List<MistakeTagController.MistakeRowView>) mistakesModel;
        assertEquals(1, rows.size());
        assertEquals(7L, rows.get(0).usageCount());
        assertTrue(rows.get(0).manageable());

        verify(tradeMistakeTagRepository).countUsageByMistakeTag();
        verify(tradeMistakeTagRepository).summarizeBySession();
        verify(tradeMistakeTagRepository).summarizeBySymbol();
        verify(tradeMistakeTagRepository).findRecentMistakes();
        verify(mistakeAnalyticsService).buildTrendReportForAdmin();
        verify(tradeMistakeTagRepository, never()).countUsageByMistakeTagForUser(admin.getId());
        verify(tradeMistakeTagRepository, never()).summarizeBySessionForUser(admin.getId());
        verify(tradeMistakeTagRepository, never()).summarizeBySymbolForUser(admin.getId());
        verify(tradeMistakeTagRepository, never()).findRecentMistakesForUser(admin.getId());
        verify(mistakeAnalyticsService, never()).buildTrendReportForUser(admin.getId());
    }

    private MistakeAnalyticsService.MistakeTrendReport emptyTrendReport() {
        return new MistakeAnalyticsService.MistakeTrendReport(
                "N/A",
                0L,
                0L,
                List.of(),
                List.of(),
                "No data"
        );
    }

    private TradeMistakeTagRepository.MistakeUsageRow usage(String mistakeTagId, long usageCount) {
        return new TradeMistakeTagRepository.MistakeUsageRow() {
            @Override
            public String getMistakeTagId() {
                return mistakeTagId;
            }

            @Override
            public long getUsageCount() {
                return usageCount;
            }
        };
    }

    private TradeMistakeTagRepository.MistakeSessionRow session(String session, long usageCount) {
        return new TradeMistakeTagRepository.MistakeSessionRow() {
            @Override
            public String getSession() {
                return session;
            }

            @Override
            public long getUsageCount() {
                return usageCount;
            }
        };
    }

    private TradeMistakeTagRepository.MistakeSymbolRow symbol(String symbol, long usageCount) {
        return new TradeMistakeTagRepository.MistakeSymbolRow() {
            @Override
            public String getSymbol() {
                return symbol;
            }

            @Override
            public long getUsageCount() {
                return usageCount;
            }
        };
    }

    private TradeMistakeTagRepository.RecentMistakeRow recent(String tradeId, String mistakeName, String session, String symbol) {
        return new TradeMistakeTagRepository.RecentMistakeRow() {
            @Override
            public String getTradeId() {
                return tradeId;
            }

            @Override
            public String getMistakeName() {
                return mistakeName;
            }

            @Override
            public String getSession() {
                return session;
            }

            @Override
            public String getSymbol() {
                return symbol;
            }

            @Override
            public LocalDateTime getEntryTime() {
                return LocalDateTime.of(2026, 3, 28, 10, 15);
            }
        };
    }
}
