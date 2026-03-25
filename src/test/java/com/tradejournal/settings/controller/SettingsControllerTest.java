package com.tradejournal.settings.controller;

import com.tradejournal.auth.domain.User;
import com.tradejournal.auth.service.UserService;
import com.tradejournal.settings.service.AccountPrivacyService;
import com.tradejournal.settings.service.SettingsService;
import com.tradejournal.trade.domain.Trade;
import com.tradejournal.trade.service.TradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class SettingsControllerTest {

    private UserService userService;
    private TradeService tradeService;
    private SettingsService settingsService;
    private AccountPrivacyService accountPrivacyService;
    private MockMvc mockMvc;
    private User currentUser;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        tradeService = mock(TradeService.class);
        settingsService = mock(SettingsService.class);
        accountPrivacyService = mock(AccountPrivacyService.class);

        SettingsController controller = new SettingsController(userService, tradeService, settingsService, accountPrivacyService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setViewResolvers((viewName, locale) -> {
                    if (viewName.startsWith("redirect:")) {
                        return new RedirectView(viewName.substring("redirect:".length()));
                    }
                    return (model, request, response) -> { };
                })
                .build();

        currentUser = new User();
        currentUser.setId("settings-user-1");
        currentUser.setUsername("tradepilot");
        currentUser.setEmail("pilot@example.com");
        currentUser.setPasswordHash("$2a$10$abcdefghijklmnopqrstuv");
        currentUser.setTimezone("Asia/Bangkok");
        currentUser.setCountry("Thailand");
        currentUser.setDefaultAccount("Personal Journal Account");
        currentUser.setPreferredCurrency("USD");
        currentUser.setRiskUnit("R_MULTIPLE");
        currentUser.setChartTimezone("America/New_York");
        currentUser.setEmailNotificationsEnabled(true);
        currentUser.setWeeklySummaryEnabled(true);
        currentUser.setBillingNotificationsEnabled(false);

        when(userService.getCurrentUser(any())).thenReturn(currentUser);
        when(userService.hasProAccess(currentUser)).thenReturn(true);
        when(userService.resolveTradeLimit(currentUser)).thenReturn(0);
        when(userService.resolvePlanLabel(currentUser)).thenReturn("Pro Plan");
        when(tradeService.findAllByUser(currentUser.getId())).thenReturn(List.<Trade>of());
        when(settingsService.updateProfile(any(), anyString(), anyString(), anyString(), anyString(), any(), anyBoolean()))
                .thenAnswer(invocation -> {
                    User user = invocation.getArgument(0);
                    user.setUsername(invocation.getArgument(1));
                    user.setEmail(invocation.getArgument(2));
                    user.setTimezone(invocation.getArgument(3));
                    user.setCountry(invocation.getArgument(4));
                    return user;
                });
        when(settingsService.updatePassword(any(), anyString(), anyString(), anyString()))
                .thenReturn(currentUser);
        when(settingsService.updateTradingPreferences(any(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    User user = invocation.getArgument(0);
                    user.setDefaultAccount(invocation.getArgument(1));
                    user.setPreferredCurrency(invocation.getArgument(2));
                    user.setRiskUnit(invocation.getArgument(3));
                    user.setChartTimezone(invocation.getArgument(4));
                    return user;
                });
        when(settingsService.updateNotifications(any(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenAnswer(invocation -> {
                    User user = invocation.getArgument(0);
                    user.setEmailNotificationsEnabled(invocation.getArgument(1));
                    user.setWeeklySummaryEnabled(invocation.getArgument(2));
                    user.setBillingNotificationsEnabled(invocation.getArgument(3));
                    return user;
                });
    }

    @Test
    void settingsPageRendersWithSettingsView() throws Exception {
        mockMvc.perform(get("/settings").session(new MockHttpSession()))
                .andExpect(status().isOk())
                .andExpect(view().name("settings"))
                .andExpect(model().attributeExists("currentUser"))
                .andExpect(model().attribute("settingsPlanLabel", "Pro Plan"));
    }

    @Test
    void updateProfileReturnsJsonForAjaxRequest() throws Exception {
        MockMultipartFile avatar = new MockMultipartFile("avatarFile", new byte[0]);

        mockMvc.perform(multipart("/settings/profile")
                        .file(avatar)
                        .param("name", "Desk Pilot")
                        .param("email", "desk@example.com")
                        .param("timezone", "Europe/London")
                        .param("country", "United Kingdom")
                        .param("removeAvatar", "false")
                        .header("Accept", "application/json")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .session(new MockHttpSession()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.section").value("profile"))
                .andExpect(jsonPath("$.viewModel.name").value("Desk Pilot"))
                .andExpect(jsonPath("$.viewModel.timezone").value("Europe/London"));
    }

    @Test
    void updateSecurityReturnsJsonErrorForAjaxRequest() throws Exception {
        when(settingsService.updatePassword(any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("Current password is incorrect"));

        mockMvc.perform(post("/settings/security")
                        .param("currentPassword", "bad")
                        .param("newPassword", "new-password-1")
                        .param("confirmPassword", "new-password-1")
                        .header("Accept", "application/json")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .session(new MockHttpSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.section").value("security"))
                .andExpect(jsonPath("$.message").value("Current password is incorrect"));
    }

    @Test
    void updatePreferencesReturnsJsonForAjaxRequest() throws Exception {
        mockMvc.perform(post("/settings/preferences")
                        .param("defaultAccount", "Swing Account")
                        .param("preferredCurrency", "EUR")
                        .param("riskUnit", "CURRENCY")
                        .param("chartTimezone", "UTC")
                        .header("Accept", "application/json")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .session(new MockHttpSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.section").value("preferences"))
                .andExpect(jsonPath("$.viewModel.defaultAccount").value("Swing Account"))
                .andExpect(jsonPath("$.viewModel.riskUnitLabel").value("Currency"));
    }

    @Test
    void updateNotificationsReturnsJsonForAjaxRequest() throws Exception {
        mockMvc.perform(post("/settings/notifications")
                        .param("emailNotificationsEnabled", "false")
                        .param("emailNotificationsEnabled", "true")
                        .param("weeklySummaryEnabled", "false")
                        .param("billingNotificationsEnabled", "false")
                        .header("Accept", "application/json")
                        .header("X-Requested-With", "XMLHttpRequest")
                        .session(new MockHttpSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.section").value("notifications"))
                .andExpect(jsonPath("$.viewModel.emailNotificationsEnabled").value(true))
                .andExpect(jsonPath("$.viewModel.billingNotificationsEnabled").value(false));
    }

    @Test
    void updateProfileRedirectsForHtmlFallback() throws Exception {
        MockMultipartFile avatar = new MockMultipartFile("avatarFile", new byte[0]);

        mockMvc.perform(multipart("/settings/profile")
                        .file(avatar)
                        .param("name", "Desk Pilot")
                        .param("email", "desk@example.com")
                        .param("timezone", "Europe/London")
                        .param("country", "United Kingdom")
                        .param("removeAvatar", "false")
                        .session(new MockHttpSession()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings#profile"))
                .andExpect(flash().attribute("profileSuccess", "Profile updated successfully."));
    }
}
