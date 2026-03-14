package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.service.AccountPrivacyService;
import com.example.demo.service.SettingsService;
import com.example.demo.service.TradeService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final AccountPrivacyService accountPrivacyService;

    public SettingsController(
            UserService userService,
            TradeService tradeService,
            SettingsService settingsService,
            AccountPrivacyService accountPrivacyService
    ) {
        this.userService = userService;
        this.tradeService = tradeService;
        this.settingsService = settingsService;
        this.accountPrivacyService = accountPrivacyService;
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

    @PostMapping("/settings/preferences")
    public String updateTradingPreferences(
            @RequestParam("defaultAccount") String defaultAccount,
            @RequestParam("preferredCurrency") String preferredCurrency,
            @RequestParam("riskUnit") String riskUnit,
            @RequestParam("chartTimezone") String chartTimezone,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        try {
            validateAllowedValue(defaultAccount, ACCOUNT_OPTIONS, "Default account");
            validateAllowedValue(preferredCurrency, CURRENCY_OPTIONS, "Currency");
            validateAllowedValue(riskUnit, RISK_UNIT_OPTIONS, "Risk unit");
            validateAllowedValue(chartTimezone, TIMEZONE_OPTIONS, "Chart timezone");
            settingsService.updateTradingPreferences(currentUser, defaultAccount, preferredCurrency, riskUnit, chartTimezone);
            redirectAttributes.addFlashAttribute("preferencesSuccess", "Trading preferences updated successfully.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("preferencesError", e.getMessage());
            redirectAttributes.addFlashAttribute("selectedDefaultAccount", defaultAccount);
            redirectAttributes.addFlashAttribute("selectedCurrency", preferredCurrency);
            redirectAttributes.addFlashAttribute("selectedRiskUnit", riskUnit);
            redirectAttributes.addFlashAttribute("selectedChartTimezone", chartTimezone);
        }

        return "redirect:/settings#preferences-card";
    }

    @PostMapping("/settings/notifications")
    public String updateNotifications(
            @RequestParam(value = "emailNotificationsEnabled", required = false) List<String> emailNotificationsEnabled,
            @RequestParam(value = "weeklySummaryEnabled", required = false) List<String> weeklySummaryEnabled,
            @RequestParam(value = "billingNotificationsEnabled", required = false) List<String> billingNotificationsEnabled,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        settingsService.updateNotifications(
                currentUser,
                containsTrue(emailNotificationsEnabled),
                containsTrue(weeklySummaryEnabled),
                containsTrue(billingNotificationsEnabled)
        );
        redirectAttributes.addFlashAttribute("notificationsSuccess", "Notification settings updated successfully.");
        return "redirect:/settings#notifications-card";
    }

    @GetMapping("/settings/export/all")
    public ResponseEntity<byte[]> exportAllData(HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return ResponseEntity.status(401).build();
        }

        AccountPrivacyService.ExportFile exportFile = accountPrivacyService.exportAllData(currentUser);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + exportFile.fileName() + "\"")
                .contentType(MediaType.parseMediaType(exportFile.contentType()))
                .body(exportFile.content());
    }

    @GetMapping("/settings/export/trades.csv")
    public ResponseEntity<byte[]> exportTradesCsv(HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return ResponseEntity.status(401).build();
        }

        AccountPrivacyService.ExportFile exportFile = accountPrivacyService.exportTradesCsv(currentUser);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + exportFile.fileName() + "\"")
                .contentType(MediaType.parseMediaType(exportFile.contentType()))
                .body(exportFile.content());
    }

    @PostMapping("/settings/delete-account")
    public String deleteAccount(
            @RequestParam("currentPassword") String currentPassword,
            @RequestParam("confirmationText") String confirmationText,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        try {
            accountPrivacyService.deleteAccount(currentUser, currentPassword, confirmationText);
            session.invalidate();
            return "redirect:/login?accountDeleted=1";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("dangerError", e.getMessage());
            return "redirect:/settings#danger-zone-card";
        }
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

        if (!model.containsAttribute("selectedDefaultAccount")) {
            model.addAttribute("selectedDefaultAccount", valueOrDefault(currentUser.getDefaultAccount(), hasProAccess ? "Pro Main Account" : "Personal Journal Account"));
        }
        if (!model.containsAttribute("selectedCurrency")) {
            model.addAttribute("selectedCurrency", valueOrDefault(currentUser.getPreferredCurrency(), "USD"));
        }
        if (!model.containsAttribute("selectedRiskUnit")) {
            model.addAttribute("selectedRiskUnit", valueOrDefault(currentUser.getRiskUnit(), "R_MULTIPLE"));
        }
        if (!model.containsAttribute("selectedChartTimezone")) {
            model.addAttribute("selectedChartTimezone", valueOrDefault(currentUser.getChartTimezone(), "America/New_York"));
        }

        model.addAttribute("timezoneOptions", TIMEZONE_OPTIONS);
        model.addAttribute("countryOptions", COUNTRY_OPTIONS);
        model.addAttribute("accountOptions", ACCOUNT_OPTIONS);
        model.addAttribute("currencyOptions", CURRENCY_OPTIONS);
        model.addAttribute("riskUnitOptions", RISK_UNIT_OPTIONS);

        if (!model.containsAttribute("emailNotificationsEnabled")) {
            model.addAttribute("emailNotificationsEnabled", boolOrDefault(currentUser.getEmailNotificationsEnabled(), true));
        }
        if (!model.containsAttribute("weeklySummaryEnabled")) {
            model.addAttribute("weeklySummaryEnabled", boolOrDefault(currentUser.getWeeklySummaryEnabled(), hasProAccess));
        }
        if (!model.containsAttribute("billingNotificationsEnabled")) {
            model.addAttribute("billingNotificationsEnabled", boolOrDefault(currentUser.getBillingNotificationsEnabled(), true));
        }
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean boolOrDefault(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private void validateAllowedValue(String submittedValue, List<String> allowedValues, String fieldName) {
        if (submittedValue == null || submittedValue.isBlank() || !allowedValues.contains(submittedValue)) {
            throw new IllegalArgumentException(fieldName + " selection is invalid");
        }
    }

    private boolean containsTrue(List<String> values) {
        return values != null && values.stream().anyMatch(value -> "true".equalsIgnoreCase(value));
    }
}
