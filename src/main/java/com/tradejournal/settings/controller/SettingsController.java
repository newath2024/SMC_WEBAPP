package com.tradejournal.settings.controller;

import com.tradejournal.auth.domain.User;
import com.tradejournal.settings.service.AccountPrivacyService;
import com.tradejournal.settings.service.SettingsService;
import com.tradejournal.trade.service.TradeService;
import com.tradejournal.auth.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public Object updateProfile(
            @RequestParam("name") String name,
            @RequestParam("email") String email,
            @RequestParam("timezone") String timezone,
            @RequestParam("country") String country,
            @RequestParam(value = "avatarFile", required = false) MultipartFile avatarFile,
            @RequestParam(value = "removeAvatar", defaultValue = "false") boolean removeAvatar,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return jsonRequested(acceptHeader, requestedWith)
                    ? ResponseEntity.status(401).body(errorResponse("profile", "Your session has expired. Please sign in again."))
                    : "redirect:/login";
        }

        try {
            User updatedUser = settingsService.updateProfile(currentUser, name, email, timezone, country, avatarFile, removeAvatar);
            if (jsonRequested(acceptHeader, requestedWith)) {
                return ResponseEntity.ok(successResponse(
                        "profile",
                        "Profile updated successfully.",
                        buildProfileViewModel(updatedUser)
                ));
            }
            redirectAttributes.addFlashAttribute("profileSuccess", "Profile updated successfully.");
        } catch (IllegalArgumentException e) {
            if (jsonRequested(acceptHeader, requestedWith)) {
                return ResponseEntity.badRequest().body(errorResponse("profile", e.getMessage()));
            }
            redirectAttributes.addFlashAttribute("profileError", e.getMessage());
            redirectAttributes.addFlashAttribute("profileFormName", name);
            redirectAttributes.addFlashAttribute("profileFormEmail", email);
            redirectAttributes.addFlashAttribute("selectedProfileTimezone", timezone);
            redirectAttributes.addFlashAttribute("selectedCountry", country);
        }

        return "redirect:/settings#profile";
    }

    @PostMapping("/settings/security")
    public Object updatePassword(
            @RequestParam("currentPassword") String currentPassword,
            @RequestParam("newPassword") String newPassword,
            @RequestParam("confirmPassword") String confirmPassword,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return jsonRequested(acceptHeader, requestedWith)
                    ? ResponseEntity.status(401).body(errorResponse("security", "Your session has expired. Please sign in again."))
                    : "redirect:/login";
        }

        try {
            settingsService.updatePassword(currentUser, currentPassword, newPassword, confirmPassword);
            if (jsonRequested(acceptHeader, requestedWith)) {
                return ResponseEntity.ok(successResponse(
                        "security",
                        "Password updated successfully.",
                        Map.of("passwordState", "Protected by password authentication")
                ));
            }
            redirectAttributes.addFlashAttribute("securitySuccess", "Password updated successfully.");
        } catch (IllegalArgumentException e) {
            if (jsonRequested(acceptHeader, requestedWith)) {
                return ResponseEntity.badRequest().body(errorResponse("security", e.getMessage()));
            }
            redirectAttributes.addFlashAttribute("securityError", e.getMessage());
        }

        return "redirect:/settings#security";
    }

    @PostMapping("/settings/preferences")
    public Object updateTradingPreferences(
            @RequestParam("defaultAccount") String defaultAccount,
            @RequestParam("preferredCurrency") String preferredCurrency,
            @RequestParam("riskUnit") String riskUnit,
            @RequestParam("chartTimezone") String chartTimezone,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return jsonRequested(acceptHeader, requestedWith)
                    ? ResponseEntity.status(401).body(errorResponse("preferences", "Your session has expired. Please sign in again."))
                    : "redirect:/login";
        }

        try {
            validateAllowedValue(defaultAccount, ACCOUNT_OPTIONS, "Default account");
            validateAllowedValue(preferredCurrency, CURRENCY_OPTIONS, "Currency");
            validateAllowedValue(riskUnit, RISK_UNIT_OPTIONS, "Risk unit");
            validateAllowedValue(chartTimezone, TIMEZONE_OPTIONS, "Chart timezone");
            User updatedUser = settingsService.updateTradingPreferences(currentUser, defaultAccount, preferredCurrency, riskUnit, chartTimezone);
            if (jsonRequested(acceptHeader, requestedWith)) {
                return ResponseEntity.ok(successResponse(
                        "preferences",
                        "Trading preferences updated successfully.",
                        buildPreferencesViewModel(updatedUser)
                ));
            }
            redirectAttributes.addFlashAttribute("preferencesSuccess", "Trading preferences updated successfully.");
        } catch (IllegalArgumentException e) {
            if (jsonRequested(acceptHeader, requestedWith)) {
                return ResponseEntity.badRequest().body(errorResponse("preferences", e.getMessage()));
            }
            redirectAttributes.addFlashAttribute("preferencesError", e.getMessage());
            redirectAttributes.addFlashAttribute("selectedDefaultAccount", defaultAccount);
            redirectAttributes.addFlashAttribute("selectedCurrency", preferredCurrency);
            redirectAttributes.addFlashAttribute("selectedRiskUnit", riskUnit);
            redirectAttributes.addFlashAttribute("selectedChartTimezone", chartTimezone);
        }

        return "redirect:/settings#preferences";
    }

    @PostMapping("/settings/notifications")
    public Object updateNotifications(
            @RequestParam(value = "emailNotificationsEnabled", required = false) List<String> emailNotificationsEnabled,
            @RequestParam(value = "weeklySummaryEnabled", required = false) List<String> weeklySummaryEnabled,
            @RequestParam(value = "billingNotificationsEnabled", required = false) List<String> billingNotificationsEnabled,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            @RequestHeader(value = "X-Requested-With", required = false) String requestedWith,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return jsonRequested(acceptHeader, requestedWith)
                    ? ResponseEntity.status(401).body(errorResponse("notifications", "Your session has expired. Please sign in again."))
                    : "redirect:/login";
        }

        User updatedUser = settingsService.updateNotifications(
                currentUser,
                containsTrue(emailNotificationsEnabled),
                containsTrue(weeklySummaryEnabled),
                containsTrue(billingNotificationsEnabled)
        );
        if (jsonRequested(acceptHeader, requestedWith)) {
            return ResponseEntity.ok(successResponse(
                    "notifications",
                    "Notification settings updated successfully.",
                    buildNotificationsViewModel(updatedUser)
            ));
        }
        redirectAttributes.addFlashAttribute("notificationsSuccess", "Notification settings updated successfully.");
        return "redirect:/settings#notifications";
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
            return "redirect:/settings#security";
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

    private boolean jsonRequested(String acceptHeader, String requestedWith) {
        return (acceptHeader != null && acceptHeader.contains(MediaType.APPLICATION_JSON_VALUE))
                || "XMLHttpRequest".equalsIgnoreCase(requestedWith);
    }

    private Map<String, Object> successResponse(String section, String message, Map<String, Object> viewModel) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("section", section);
        response.put("message", message);
        response.put("fieldErrors", Map.of());
        response.put("viewModel", viewModel);
        return response;
    }

    private Map<String, Object> errorResponse(String section, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "error");
        response.put("section", section);
        response.put("message", message);
        response.put("fieldErrors", Map.of());
        response.put("viewModel", Map.of());
        return response;
    }

    private Map<String, Object> buildProfileViewModel(User user) {
        Map<String, Object> viewModel = new LinkedHashMap<>();
        viewModel.put("name", valueOrDefault(user.getUsername(), ""));
        viewModel.put("email", valueOrDefault(user.getEmail(), ""));
        viewModel.put("timezone", valueOrDefault(user.getTimezone(), TIMEZONE_OPTIONS.get(0)));
        viewModel.put("country", valueOrDefault(user.getCountry(), COUNTRY_OPTIONS.get(0)));
        viewModel.put("avatarDataUrl", user.getAvatarDataUrl());
        viewModel.put("avatarInitial", resolveAvatarInitial(user.getUsername()));
        return viewModel;
    }

    private Map<String, Object> buildPreferencesViewModel(User user) {
        Map<String, Object> viewModel = new LinkedHashMap<>();
        viewModel.put("defaultAccount", valueOrDefault(user.getDefaultAccount(), ACCOUNT_OPTIONS.get(0)));
        viewModel.put("preferredCurrency", valueOrDefault(user.getPreferredCurrency(), CURRENCY_OPTIONS.get(0)));
        viewModel.put("riskUnit", valueOrDefault(user.getRiskUnit(), RISK_UNIT_OPTIONS.get(0)));
        viewModel.put("riskUnitLabel", "R_MULTIPLE".equalsIgnoreCase(user.getRiskUnit()) ? "R multiple" : "Currency");
        viewModel.put("chartTimezone", valueOrDefault(user.getChartTimezone(), TIMEZONE_OPTIONS.get(0)));
        return viewModel;
    }

    private Map<String, Object> buildNotificationsViewModel(User user) {
        boolean emailEnabled = boolOrDefault(user.getEmailNotificationsEnabled(), true);
        boolean weeklyEnabled = boolOrDefault(user.getWeeklySummaryEnabled(), false);
        boolean billingEnabled = boolOrDefault(user.getBillingNotificationsEnabled(), true);

        Map<String, Object> viewModel = new LinkedHashMap<>();
        viewModel.put("emailNotificationsEnabled", emailEnabled);
        viewModel.put("emailNotificationsBadge", emailEnabled ? "On" : "Off");
        viewModel.put("emailNotificationsSummary", emailEnabled
                ? "Enabled for important account activity."
                : "Disabled.");
        viewModel.put("weeklySummaryEnabled", weeklyEnabled);
        viewModel.put("weeklySummaryBadge", weeklyEnabled ? "On" : "Off");
        viewModel.put("weeklySummarySummary", weeklyEnabled
                ? "Weekly summaries are enabled."
                : "Weekly summaries are disabled.");
        viewModel.put("billingNotificationsEnabled", billingEnabled);
        viewModel.put("billingNotificationsBadge", billingEnabled ? "On" : "Off");
        viewModel.put("billingNotificationsSummary", billingEnabled
                ? "Billing notices are enabled."
                : "Billing notices are disabled.");
        return viewModel;
    }

    private String resolveAvatarInitial(String username) {
        String value = valueOrDefault(username, "U").trim();
        return value.isEmpty() ? "U" : value.substring(0, 1).toUpperCase();
    }
}
