package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.service.SettingsService;
import com.example.demo.service.TradeService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Controller
public class SettingsController {

    private static final DateTimeFormatter MEMBER_SINCE_FORMAT = DateTimeFormatter.ofPattern("MMM yyyy");
    private static final List<String> TIMEZONE_OPTIONS = List.of("Asia/Bangkok", "Europe/London", "America/New_York", "UTC");
    private static final List<String> COUNTRY_OPTIONS = List.of("Thailand", "United States", "United Kingdom", "Singapore", "Australia");
    private static final List<String> ACCOUNT_OPTIONS = List.of("Personal Journal Account", "Swing Account", "Pro Main Account", "Evaluation Account");
    private static final List<String> CURRENCY_OPTIONS = List.of("USD", "EUR", "GBP", "THB", "JPY");
    private static final List<String> RISK_UNIT_OPTIONS = List.of("R_MULTIPLE", "CURRENCY");

    private final UserService userService;
    private final TradeService tradeService;
    private final SettingsService settingsService;

    public SettingsController(UserService userService, TradeService tradeService, SettingsService settingsService) {
        this.userService = userService;
        this.tradeService = tradeService;
        this.settingsService = settingsService;
    }

    @GetMapping("/settings")
    public String settingsPage(Model model, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        populateSettingsModel(model, currentUser);

        return "settings";
    }

    @PostMapping("/settings/profile")
    public String updateProfile(
            @RequestParam("name") String name,
            @RequestParam("email") String email,
            @RequestParam("timezone") String timezone,
            @RequestParam("country") String country,
            @RequestParam(value = "avatarFile", required = false) MultipartFile avatarFile,
            @RequestParam(value = "removeAvatar", defaultValue = "false") boolean removeAvatar,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        try {
            settingsService.updateProfile(currentUser, name, email, timezone, country, avatarFile, removeAvatar);
            redirectAttributes.addFlashAttribute("profileSuccess", "Profile updated successfully.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("profileError", e.getMessage());
            redirectAttributes.addFlashAttribute("profileFormName", name);
            redirectAttributes.addFlashAttribute("profileFormEmail", email);
            redirectAttributes.addFlashAttribute("selectedProfileTimezone", timezone);
            redirectAttributes.addFlashAttribute("selectedCountry", country);
        }

        return "redirect:/settings#profile-card";
    }

    @PostMapping("/settings/security")
    public String updatePassword(
            @RequestParam("currentPassword") String currentPassword,
            @RequestParam("newPassword") String newPassword,
            @RequestParam("confirmPassword") String confirmPassword,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        try {
            settingsService.updatePassword(currentUser, currentPassword, newPassword, confirmPassword);
            redirectAttributes.addFlashAttribute("securitySuccess", "Password updated successfully.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("securityError", e.getMessage());
        }

        return "redirect:/settings#security-card";
    }

    private void populateSettingsModel(Model model, User currentUser) {
        boolean hasProAccess = userService.hasProAccess(currentUser);
        int tradeLimit = userService.resolveTradeLimit(currentUser);
        int tradeCount = tradeService.findAllByUser(currentUser.getId()).size();

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("hasProAccess", hasProAccess);
        model.addAttribute("tradeUsage", hasProAccess ? tradeCount : Math.min(tradeCount, tradeLimit));
        model.addAttribute("tradeUsageLimit", hasProAccess ? 0 : tradeLimit);
        model.addAttribute("period", "ALL");
        model.addAttribute("from", null);
        model.addAttribute("to", null);
        model.addAttribute("selectedUserId", currentUser.getId());

        model.addAttribute("memberSince", currentUser.getCreatedAt() != null
                ? currentUser.getCreatedAt().format(MEMBER_SINCE_FORMAT)
                : LocalDate.now().format(MEMBER_SINCE_FORMAT));
        model.addAttribute("settingsPlanLabel", userService.resolvePlanLabel(currentUser));

        if (!model.containsAttribute("profileFormName")) {
            model.addAttribute("profileFormName", currentUser.getUsername());
        }
        if (!model.containsAttribute("profileFormEmail")) {
            model.addAttribute("profileFormEmail", currentUser.getEmail());
        }
        if (!model.containsAttribute("selectedProfileTimezone")) {
            model.addAttribute("selectedProfileTimezone", valueOrDefault(currentUser.getTimezone(), TIMEZONE_OPTIONS.get(0)));
        }
        if (!model.containsAttribute("selectedCountry")) {
            model.addAttribute("selectedCountry", valueOrDefault(currentUser.getCountry(), COUNTRY_OPTIONS.get(0)));
        }
        if (!model.containsAttribute("profileAvatarDataUrl")) {
            model.addAttribute("profileAvatarDataUrl", currentUser.getAvatarDataUrl());
        }

        model.addAttribute("selectedDefaultAccount", hasProAccess ? "Pro Main Account" : "Personal Journal Account");
        model.addAttribute("selectedCurrency", "USD");
        model.addAttribute("selectedRiskUnit", "R_MULTIPLE");
        model.addAttribute("selectedChartTimezone", "America/New_York");

        model.addAttribute("timezoneOptions", TIMEZONE_OPTIONS);
        model.addAttribute("countryOptions", COUNTRY_OPTIONS);
        model.addAttribute("accountOptions", ACCOUNT_OPTIONS);
        model.addAttribute("currencyOptions", CURRENCY_OPTIONS);
        model.addAttribute("riskUnitOptions", RISK_UNIT_OPTIONS);

        model.addAttribute("emailNotificationsEnabled", true);
        model.addAttribute("weeklySummaryEnabled", hasProAccess);
        model.addAttribute("billingNotificationsEnabled", true);
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
