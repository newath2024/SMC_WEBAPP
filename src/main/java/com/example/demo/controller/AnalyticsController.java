package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.service.AnalyticsService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

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
    public String overview(Model model, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        AnalyticsService.AnalyticsReport report = analyticsService.buildReportForUser(currentUser.getId());

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("report", report);
        model.addAttribute("overview", report.getOverview());

        return "analytics";
    }
}
