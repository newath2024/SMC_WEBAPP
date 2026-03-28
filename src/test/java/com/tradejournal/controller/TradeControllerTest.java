package com.tradejournal.controller;

import com.tradejournal.ai.integration.TradingViewChartImportService;
import com.tradejournal.ai.service.AiTradeReviewService;
import com.tradejournal.auth.domain.User;
import com.tradejournal.auth.service.UserService;
import com.tradejournal.mistake.domain.MistakeTag;
import com.tradejournal.mistake.service.MistakeTagService;
import com.tradejournal.setup.domain.Setup;
import com.tradejournal.setup.service.SetupService;
import com.tradejournal.trade.controller.TradeController;
import com.tradejournal.trade.domain.Trade;
import com.tradejournal.trade.repository.TradeReviewRepository;
import com.tradejournal.trade.service.TradeImageService;
import com.tradejournal.trade.service.TradeImportService;
import com.tradejournal.trade.service.TradeReviewService;
import com.tradejournal.trade.service.TradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class TradeControllerTest {

    private TradeService tradeService;
    private SetupService setupService;
    private MistakeTagService mistakeTagService;
    private TradeImageService tradeImageService;
    private UserService userService;
    private TradingViewChartImportService tradingViewChartImportService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tradeService = mock(TradeService.class);
        userService = mock(UserService.class);
        setupService = mock(SetupService.class);
        mistakeTagService = mock(MistakeTagService.class);
        tradeImageService = mock(TradeImageService.class);
        TradeReviewService tradeReviewService = mock(TradeReviewService.class);
        TradeReviewRepository tradeReviewRepository = mock(TradeReviewRepository.class);
        TradeImportService tradeImportService = mock(TradeImportService.class);
        tradingViewChartImportService = mock(TradingViewChartImportService.class);
        AiTradeReviewService aiTradeReviewService = mock(AiTradeReviewService.class);

        TradeController controller = new TradeController(
                tradeService,
                userService,
                setupService,
                mistakeTagService,
                tradeImageService,
                tradeReviewService,
                tradeReviewRepository,
                tradeImportService,
                tradingViewChartImportService,
                aiTradeReviewService
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
    void editFormUsesTradeOwnerCollectionsForAdmin() throws Exception {
        User admin = new User();
        admin.setId("admin-1");
        admin.setRole("ADMIN");

        User owner = new User();
        owner.setId("owner-1");

        Trade trade = new Trade();
        trade.setId("trade-1");
        trade.setUser(owner);

        Setup ownerSetup = new Setup();
        ownerSetup.setId("setup-1");
        ownerSetup.setName("Owner Setup");
        List<Setup> ownerSetups = List.of(ownerSetup);

        MistakeTag ownerTag = new MistakeTag();
        ownerTag.setId("mistake-1");
        ownerTag.setName("Owner Mistake");
        List<MistakeTag> ownerTags = List.of(ownerTag);

        when(userService.getCurrentUser(any())).thenReturn(admin);
        when(userService.isAdmin(admin)).thenReturn(true);
        when(tradeService.findByIdForAdmin("trade-1")).thenReturn(trade);
        when(tradeImageService.findByTradeId("trade-1")).thenReturn(List.of());
        when(setupService.findActiveByUser("owner-1")).thenReturn(ownerSetups);
        when(mistakeTagService.findActiveForUser("owner-1")).thenReturn(ownerTags);
        when(tradeService.findAllByUser("owner-1")).thenReturn(List.of());
        when(userService.resolveTradeLimit(owner)).thenReturn(100);
        when(userService.hasProAccess(owner)).thenReturn(false);
        when(tradingViewChartImportService.isConfigured()).thenReturn(false);

        mockMvc.perform(get("/trades/{id}/edit", "trade-1").session(new MockHttpSession()))
                .andExpect(status().isOk())
                .andExpect(view().name("tradeForm"))
                .andExpect(model().attribute("setups", ownerSetups))
                .andExpect(model().attribute("mistakeTags", ownerTags));
    }
}
