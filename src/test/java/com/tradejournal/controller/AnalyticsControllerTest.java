package com.tradejournal.controller;

import com.tradejournal.analytics.controller.AnalyticsController;
import com.tradejournal.analytics.service.AnalyticsService;
import com.tradejournal.analytics.service.WeeklyCoachReportGenerator;
import com.tradejournal.auth.domain.User;
import com.tradejournal.auth.repository.UserRepository;
import com.tradejournal.auth.service.UserService;
import com.tradejournal.trade.repository.TradeReviewRepository;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@ExtendWith(MockitoExtension.class)
class AnalyticsControllerTest {

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TradeReviewRepository tradeReviewRepository;

    @Mock
    private WeeklyCoachReportGenerator weeklyCoachReportGenerator;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AnalyticsController controller = new AnalyticsController(
                analyticsService,
                userService,
                userRepository,
                tradeReviewRepository,
                weeklyCoachReportGenerator
        );
        InternalResourceViewResolver viewResolver = new InternalResourceViewResolver();
        viewResolver.setPrefix("/WEB-INF/views/");
        viewResolver.setSuffix(".html");
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setViewResolvers(viewResolver)
                .build();
    }

    @Test
    void overviewUsesSelectedUserPlanStateAndAdminSafeLinks() throws Exception {
        User admin = new User();
        admin.setId("admin-1");
        admin.setUsername("admin");
        admin.setRole("ADMIN");

        User trader = new User();
        trader.setId("user-1");
        trader.setUsername("trader");

        AnalyticsService.TradeOverview overview = new AnalyticsService.TradeOverview(
                12, 7, 4, 1, 58.3, 210.0, 17.5, 6.0, 0.5
        );
        AnalyticsService.AnalyticsReport report = new AnalyticsService.AnalyticsReport(
                overview,
                new AnalyticsService.RiskMetrics(1.2, 80.0, 0.2, 1.1, -0.9),
                new AnalyticsService.ProcessMetrics(5, 3, 41.6, 60.0, 0.4, 1, 1, 2, 2, 1, 1, 1, 0, 0, List.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        AnalyticsService.PeriodComparison comparison = new AnalyticsService.PeriodComparison(
                overview,
                overview,
                0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        );
        WeeklyCoachReportGenerator.WeeklyCoachReport weeklyCoach = new WeeklyCoachReportGenerator.WeeklyCoachReport(
                LocalDate.of(2026, 3, 22),
                LocalDate.of(2026, 3, 28),
                4,
                78,
                3,
                2,
                2,
                2,
                false,
                "Good week overall.",
                List.of("Stayed selective."),
                List.of(new WeeklyCoachReportGenerator.CoachIssue(1, "Early entry", "Wait for confirmation.")),
                List.of("Reduce impulsive entries."),
                List.of("Trade only A setups."),
                "Confidence is moderate."
        );

        when(userService.getCurrentUser(any(HttpSession.class))).thenReturn(admin);
        when(userService.isAdmin(admin)).thenReturn(true);
        when(userRepository.findAll(any(Sort.class))).thenReturn(List.of(admin, trader));
        when(userRepository.findById("user-1")).thenReturn(Optional.of(trader));
        when(userService.hasProAccess(admin)).thenReturn(true);
        when(userService.hasProAccess(trader)).thenReturn(false);
        when(userService.resolveTradeLimit(trader)).thenReturn(100);
        when(analyticsService.countTradesForUser("user-1")).thenReturn(180L);
        when(analyticsService.buildReportForUser(eq("user-1"), any(), any())).thenReturn(report);
        when(analyticsService.buildPeriodComparisonForUser(eq("user-1"), any(), any())).thenReturn(comparison);
        when(tradeReviewRepository.findTopByTradeUserIdAndQualityScoreIsNotNullOrderByUpdatedAtDesc("user-1"))
                .thenReturn(Optional.empty());
        when(weeklyCoachReportGenerator.buildReportForUser("user-1")).thenReturn(weeklyCoach);

        mockMvc.perform(get("/dashboard").param("userId", "user-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attribute("selectedUserId", "user-1"))
                .andExpect(model().attribute("hasProAccess", true))
                .andExpect(model().attribute("targetHasProAccess", false))
                .andExpect(model().attribute("tradeUsage", 100L))
                .andExpect(model().attribute("tradeUsageLimit", 100))
                .andExpect(model().attribute("tradeLimitReached", true))
                .andExpect(model().attribute("aiReviewedTradesUrl", "/admin/users/user-1"))
                .andExpect(model().attribute("aiReviewedTradesActionLabel", "Open account detail"))
                .andExpect(model().attribute("weeklyCoachReportUrl", "/reports/weekly?userId=user-1"))
                .andExpect(model().attribute("settingsUrl", "/admin/settings"));

        verify(weeklyCoachReportGenerator).buildReportForUser("user-1");
    }

    @Test
    void overviewKeepsDirectTradeLinksForOwnAccount() throws Exception {
        User user = new User();
        user.setId("user-1");
        user.setUsername("trader");

        AnalyticsService.TradeOverview overview = new AnalyticsService.TradeOverview(
                12, 7, 4, 1, 58.3, 210.0, 17.5, 6.0, 0.5
        );
        AnalyticsService.AnalyticsReport report = new AnalyticsService.AnalyticsReport(
                overview,
                new AnalyticsService.RiskMetrics(1.2, 80.0, 0.2, 1.1, -0.9),
                new AnalyticsService.ProcessMetrics(5, 3, 41.6, 60.0, 0.4, 1, 1, 2, 2, 1, 1, 1, 0, 0, List.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        AnalyticsService.PeriodComparison comparison = new AnalyticsService.PeriodComparison(
                overview,
                overview,
                0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        );

        when(userService.getCurrentUser(any(HttpSession.class))).thenReturn(user);
        when(userService.isAdmin(user)).thenReturn(false);
        when(userService.hasProAccess(user)).thenReturn(false);
        when(userService.resolveTradeLimit(user)).thenReturn(100);
        when(analyticsService.countTradesForUser("user-1")).thenReturn(42L);
        when(analyticsService.buildReportForUser(eq("user-1"), any(), any())).thenReturn(report);
        when(analyticsService.buildPeriodComparisonForUser(eq("user-1"), any(), any())).thenReturn(comparison);
        when(tradeReviewRepository.findTopByTradeUserIdAndQualityScoreIsNotNullOrderByUpdatedAtDesc("user-1"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attribute("tradeUsage", 42L))
                .andExpect(model().attribute("tradeUsageLimit", 100))
                .andExpect(model().attribute("tradeLimitReached", false))
                .andExpect(model().attribute("aiReviewedTradesUrl", "/trades?view=ai-reviewed"))
                .andExpect(model().attribute("aiReviewedTradesActionLabel", "Open AI-reviewed trades"))
                .andExpect(model().attribute("weeklyCoachReportUrl", "/reports/weekly"))
                .andExpect(model().attribute("settingsUrl", "/settings"));

        verify(weeklyCoachReportGenerator, never()).buildReportForUser(anyString());
    }

    @Test
    void weeklyReportUsesActualTradeUsageAndAdminNavigationUrls() throws Exception {
        User admin = new User();
        admin.setId("admin-1");
        admin.setUsername("admin");
        admin.setRole("ADMIN");

        User trader = new User();
        trader.setId("user-1");
        trader.setUsername("trader");

        WeeklyCoachReportGenerator.WeeklyCoachReport weeklyCoach = new WeeklyCoachReportGenerator.WeeklyCoachReport(
                LocalDate.of(2026, 3, 22),
                LocalDate.of(2026, 3, 28),
                4,
                78,
                3,
                2,
                2,
                2,
                false,
                "Good week overall.",
                List.of("Stayed selective."),
                List.of(new WeeklyCoachReportGenerator.CoachIssue(1, "Early entry", "Wait for confirmation.")),
                List.of("Reduce impulsive entries."),
                List.of("Trade only A setups."),
                "Confidence is moderate."
        );

        when(userService.getCurrentUser(any(HttpSession.class))).thenReturn(admin);
        when(userService.hasProAccess(admin)).thenReturn(true);
        when(userService.isAdmin(admin)).thenReturn(true);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(trader));
        when(userService.hasProAccess(trader)).thenReturn(false);
        when(userService.resolveTradeLimit(trader)).thenReturn(100);
        when(analyticsService.countTradesForUser("user-1")).thenReturn(140L);
        when(weeklyCoachReportGenerator.buildReportForUser("user-1")).thenReturn(weeklyCoach);

        mockMvc.perform(get("/reports/weekly").param("userId", "user-1"))
                .andExpect(status().isOk())
                .andExpect(view().name("weeklyReport"))
                .andExpect(model().attribute("selectedUserId", "user-1"))
                .andExpect(model().attribute("tradeUsage", 100L))
                .andExpect(model().attribute("tradeUsageLimit", 100))
                .andExpect(model().attribute("reportsHomeUrl", "/admin/reports"))
                .andExpect(model().attribute("settingsUrl", "/admin/settings"));
    }
}
