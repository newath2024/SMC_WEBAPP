package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.service.AnalyticsService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;

@Controller
@RequestMapping("/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final UserService userService;

    public AnalyticsController(AnalyticsService analyticsService, UserService userService) {
        this.analyticsService = analyticsService;
        this.userService = userService;
    }

    @GetMapping
    public String overview(
            @RequestParam(value = "period", required = false, defaultValue = "ALL") String period,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Model model,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        String selectedPeriod = normalizePeriod(period);
        LocalDate today = LocalDate.now();

        if (!"CUSTOM".equals(selectedPeriod)) {
            from = null;
            to = null;
            if ("7D".equals(selectedPeriod)) {
                from = today.minusDays(6);
                to = today;
            } else if ("30D".equals(selectedPeriod)) {
                from = today.minusDays(29);
                to = today;
            } else if ("90D".equals(selectedPeriod)) {
                from = today.minusDays(89);
                to = today;
            }
        }

        if (from != null && to != null && from.isAfter(to)) {
            LocalDate temp = from;
            from = to;
            to = temp;
        }

        LocalDateTime fromDateTime = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDateTime = to != null ? to.atTime(LocalTime.MAX) : null;

        AnalyticsService.AnalyticsReport report = analyticsService.buildReportForUser(
                currentUser.getId(),
                fromDateTime,
                toDateTime
        );

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("report", report);
        model.addAttribute("overview", report.getOverview());
        model.addAttribute("period", selectedPeriod);
        model.addAttribute("from", from);
        model.addAttribute("to", to);

        return "analytics";
    }

    private String normalizePeriod(String period) {
        if (period == null || period.isBlank()) {
            return "ALL";
        }
        String normalized = period.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "7D", "30D", "90D", "CUSTOM", "ALL" -> normalized;
            default -> "ALL";
        };
    }
}
