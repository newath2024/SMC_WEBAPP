package com.tradejournal.controller;

import com.tradejournal.auth.domain.User;
import com.tradejournal.auth.service.UserService;
import com.tradejournal.trade.controller.TradeImageApiController;
import com.tradejournal.trade.domain.Trade;
import com.tradejournal.trade.service.TradeImageService;
import com.tradejournal.trade.service.TradeService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TradeImageApiControllerTest {

    @Mock
    private TradeService tradeService;

    @Mock
    private TradeImageService tradeImageService;

    @Mock
    private UserService userService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TradeImageApiController controller = new TradeImageApiController(tradeService, tradeImageService, userService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void uploadImagesRejectsRequestWithoutNonEmptyFiles() throws Exception {
        User user = new User();
        user.setId("user-1");
        Trade trade = new Trade();
        trade.setId("trade-1");
        trade.setUser(user);

        when(userService.getCurrentUser(any(HttpSession.class))).thenReturn(user);
        when(userService.isAdmin(user)).thenReturn(false);
        when(tradeService.findEditableByIdForUser("trade-1", "user-1")).thenReturn(trade);
        when(userService.resolveImageLimitPerTrade(user)).thenReturn(1);
        when(tradeImageService.findByTradeId("trade-1")).thenReturn(List.of());

        mockMvc.perform(multipart("/api/trades/{id}/images", "trade-1")
                        .file(new MockMultipartFile("files", "", "image/png", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose at least one image to upload."));

        verify(tradeImageService, never()).saveSetupImages(eq(trade), any(), anyString());
    }

    @Test
    void uploadImagesUsesTradeOwnerPlanLimitsForAdmin() throws Exception {
        User admin = new User();
        admin.setId("admin-1");
        admin.setRole("ADMIN");

        User owner = new User();
        owner.setId("user-1");

        Trade trade = new Trade();
        trade.setId("trade-1");
        trade.setUser(owner);

        when(userService.getCurrentUser(any(HttpSession.class))).thenReturn(admin);
        when(userService.isAdmin(admin)).thenReturn(true);
        when(tradeService.findByIdForAdmin("trade-1")).thenReturn(trade);
        when(userService.resolveImageLimitPerTrade(owner)).thenReturn(1);
        when(userService.hasProAccess(owner)).thenReturn(false);
        when(tradeImageService.findByTradeId("trade-1")).thenReturn(List.of(mock(com.tradejournal.trade.domain.TradeImage.class)));

        mockMvc.perform(multipart("/api/trades/{id}/images", "trade-1")
                        .file(new MockMultipartFile("files", "chart.png", "image/png", new byte[]{1, 2, 3})))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Standard plan allows 1 image per trade. Upgrade to Pro for unlimited screenshots."));

        verify(userService).resolveImageLimitPerTrade(owner);
        verify(userService).hasProAccess(owner);
        verify(tradeImageService, never()).saveSetupImages(eq(trade), any(), anyString());
    }
}
