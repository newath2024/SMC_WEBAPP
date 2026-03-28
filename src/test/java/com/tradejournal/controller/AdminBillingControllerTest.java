package com.tradejournal.controller;

import com.tradejournal.admin.controller.AdminBillingController;
import com.tradejournal.auth.domain.User;
import com.tradejournal.auth.repository.UserRepository;
import com.tradejournal.auth.service.UserService;
import com.tradejournal.billing.repository.BillingInvoiceRepository;
import com.tradejournal.billing.repository.BillingSubscriptionRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminBillingControllerTest {

    private UserRepository userRepository;
    private UserService userService;
    private BillingSubscriptionRepository billingSubscriptionRepository;
    private BillingInvoiceRepository billingInvoiceRepository;
    private User adminUser;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userService = mock(UserService.class);
        billingSubscriptionRepository = mock(BillingSubscriptionRepository.class);
        billingInvoiceRepository = mock(BillingInvoiceRepository.class);

        AdminBillingController controller = new AdminBillingController(
                userRepository,
                userService,
                billingSubscriptionRepository,
                billingInvoiceRepository
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
    void cancelSubscriptionRedirectsWhenRequesterIsAnonymous() throws Exception {
        when(userService.getCurrentUser(any())).thenReturn(null);

        mockMvc.perform(post("/admin/billing/subscriptions/{userId}/cancel", "user-1").session(new MockHttpSession()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        verify(userRepository, never()).findById(any());
    }

    @Test
    void changePlanRedirectsWhenRequesterIsNotAdmin() throws Exception {
        User regularUser = new User();
        regularUser.setId("user-2");
        regularUser.setRole("USER");
        when(userService.getCurrentUser(any())).thenReturn(regularUser);
        when(userService.isAdmin(regularUser)).thenReturn(false);

        mockMvc.perform(post("/admin/billing/subscriptions/{userId}/change-plan", "user-1").session(new MockHttpSession()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/trades"));

        verify(userRepository, never()).findById(any());
    }

    @Test
    void retryPaymentRedirectsWhenUserDoesNotExist() throws Exception {
        when(userRepository.findById("missing-user")).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/billing/subscriptions/{userId}/retry-payment", "missing-user").session(new MockHttpSession()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/billing"));

        verify(billingSubscriptionRepository, never()).save(any());
        verify(billingInvoiceRepository, never()).save(any());
    }
}
