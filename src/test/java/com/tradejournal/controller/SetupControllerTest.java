package com.tradejournal.controller;

import com.tradejournal.auth.domain.User;
import com.tradejournal.auth.service.UserService;
import com.tradejournal.setup.controller.SetupController;
import com.tradejournal.setup.service.SetupService;
import com.tradejournal.trade.repository.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.RedirectView;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SetupControllerTest {

    private UserService userService;
    private SetupService setupService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        setupService = mock(SetupService.class);
        TradeRepository tradeRepository = mock(TradeRepository.class);

        SetupController controller = new SetupController(setupService, userService, tradeRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setViewResolvers((viewName, locale) -> {
                    if (viewName.startsWith("redirect:")) {
                        return new RedirectView(viewName.substring("redirect:".length()));
                    }
                    return (model, request, response) -> { };
                })
                .build();

        User currentUser = new User();
        currentUser.setId("user-1");
        when(userService.getCurrentUser(any())).thenReturn(currentUser);
        when(userService.isAdmin(currentUser)).thenReturn(false);
    }

    @Test
    void editFormRedirectsWhenSetupDoesNotBelongToCurrentUser() throws Exception {
        when(setupService.findByIdForUser("missing-setup", "user-1"))
                .thenThrow(new IllegalArgumentException("Setup not found"));

        mockMvc.perform(get("/setups/{id}/edit", "missing-setup").session(new MockHttpSession()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setups"));
    }
}
