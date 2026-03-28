package com.tradejournal.controller;

import com.tradejournal.admin.controller.AdminController;
import com.tradejournal.auth.domain.User;
import com.tradejournal.auth.repository.UserRepository;
import com.tradejournal.auth.service.UserService;
import com.tradejournal.mistake.repository.MistakeTagRepository;
import com.tradejournal.setup.repository.SetupRepository;
import com.tradejournal.trade.repository.TradeMistakeTagRepository;
import com.tradejournal.trade.repository.TradeRepository;
import com.tradejournal.trade.service.TradeImageService;
import com.tradejournal.trade.service.TradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminControllerTest {

    private UserRepository userRepository;
    private TradeRepository tradeRepository;
    private TradeImageService tradeImageService;
    private TradeService tradeService;
    private User adminUser;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tradeRepository = mock(TradeRepository.class);
        SetupRepository setupRepository = mock(SetupRepository.class);
        MistakeTagRepository mistakeTagRepository = mock(MistakeTagRepository.class);
        TradeMistakeTagRepository tradeMistakeTagRepository = mock(TradeMistakeTagRepository.class);
        UserService userService = mock(UserService.class);
        tradeImageService = mock(TradeImageService.class);
        tradeService = mock(TradeService.class);

        AdminController controller = new AdminController(
                userRepository,
                tradeRepository,
                setupRepository,
                mistakeTagRepository,
                tradeMistakeTagRepository,
                userService,
                tradeImageService,
                tradeService
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setViewResolvers((viewName, locale) -> {
                    if (viewName.startsWith("redirect:")) {
                        return new RedirectView(viewName.substring("redirect:".length()));
                    }
                    return (model, request, response) -> { };
                })
                .build();

        adminUser = new User();
        adminUser.setId("admin-1");
        adminUser.setRole("ADMIN");

        when(userService.getCurrentUser(any())).thenReturn(adminUser);
        when(userService.isAdmin(adminUser)).thenReturn(true);
    }

    @Test
    void userDetailRedirectsWhenUserDoesNotExist() throws Exception {
        when(userRepository.findById("missing-user")).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/users/{id}", "missing-user").session(new MockHttpSession()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"));
    }

    @Test
    void toggleUserActiveRedirectsWhenAdminTargetsSelf() throws Exception {
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(adminUser));

        mockMvc.perform(post("/admin/users/{id}/toggle-active", "admin-1").session(new MockHttpSession()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void upgradeUserToProRedirectsWhenUserDoesNotExist() throws Exception {
        when(userRepository.findById("missing-user")).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/users/{id}/upgrade-to-pro", "missing-user").session(new MockHttpSession()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void deleteTradeRedirectsWhenTradeDoesNotExist() throws Exception {
        when(tradeRepository.findById("missing-trade")).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/trades/{id}/delete", "missing-trade").session(new MockHttpSession()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));

        verify(tradeImageService, never()).deleteByTradeId(any());
        verify(tradeService, never()).deleteForAdmin(any());
    }
}
