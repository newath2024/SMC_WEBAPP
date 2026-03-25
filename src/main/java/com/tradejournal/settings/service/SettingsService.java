package com.tradejournal.service;

import com.tradejournal.entity.User;
import com.tradejournal.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.Locale;

@Service
public class SettingsService {

    private static final long MAX_AVATAR_SIZE_BYTES = 5L * 1024L * 1024L;
    private static final String DEFAULT_TIMEZONE = "Asia/Bangkok";
    private static final String DEFAULT_COUNTRY = "Thailand";

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public SettingsService(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    public User updateProfile(
            User currentUser,
            String username,
            String email,
            String timezone,
            String country,
            MultipartFile avatarFile,
            boolean removeAvatar
    ) {
        String normalizedUsername = normalize(username);
        String normalizedEmail = normalize(email).toLowerCase(Locale.ROOT);
        String normalizedTimezone = defaultIfBlank(normalize(timezone), DEFAULT_TIMEZONE);
        String normalizedCountry = defaultIfBlank(normalize(country), DEFAULT_COUNTRY);

        validateProfileInput(currentUser, normalizedUsername, normalizedEmail);

        currentUser.setUsername(normalizedUsername);
        currentUser.setEmail(normalizedEmail);
        currentUser.setTimezone(normalizedTimezone);
        currentUser.setCountry(normalizedCountry);

        if (avatarFile != null && !avatarFile.isEmpty()) {
            currentUser.setAvatarDataUrl(toAvatarDataUrl(avatarFile));
        } else if (removeAvatar) {
            currentUser.setAvatarDataUrl(null);
        }

        return userRepository.save(currentUser);
    }

    public User updatePassword(
            User currentUser,
            String currentPassword,
            String newPassword,
            String confirmPassword
    ) {
        String currentPasswordValue = normalize(currentPassword);
        String newPasswordValue = normalize(newPassword);
        String confirmPasswordValue = normalize(confirmPassword);

        if (currentPasswordValue.isBlank()) {
            throw new IllegalArgumentException("Current password is required");
        }

        if (!passwordEncoder.matches(currentPasswordValue, currentUser.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }

        if (newPasswordValue.isBlank()) {
            throw new IllegalArgumentException("New password must not be blank");
        }

        if (newPasswordValue.length() < 8) {
            throw new IllegalArgumentException("New password must be at least 8 characters");
        }

        if (!newPasswordValue.equals(confirmPasswordValue)) {
            throw new IllegalArgumentException("Confirm new password does not match");
        }

        if (passwordEncoder.matches(newPasswordValue, currentUser.getPasswordHash())) {
            throw new IllegalArgumentException("New password must be different from your current password");
        }

        currentUser.setPasswordHash(passwordEncoder.encode(newPasswordValue));
        return userRepository.save(currentUser);
    }

    public User updateTradingPreferences(
            User currentUser,
            String defaultAccount,
            String preferredCurrency,
            String riskUnit,
            String chartTimezone
    ) {
        currentUser.setDefaultAccount(normalize(defaultAccount));
        currentUser.setPreferredCurrency(normalize(preferredCurrency).toUpperCase(Locale.ROOT));
        currentUser.setRiskUnit(normalize(riskUnit).toUpperCase(Locale.ROOT));
        currentUser.setChartTimezone(defaultIfBlank(normalize(chartTimezone), DEFAULT_TIMEZONE));
        return userRepository.save(currentUser);
    }

    public User updateNotifications(
            User currentUser,
            boolean emailNotificationsEnabled,
            boolean weeklySummaryEnabled,
            boolean billingNotificationsEnabled
    ) {
        currentUser.setEmailNotificationsEnabled(emailNotificationsEnabled);
        currentUser.setWeeklySummaryEnabled(weeklySummaryEnabled);
        currentUser.setBillingNotificationsEnabled(billingNotificationsEnabled);
        return userRepository.save(currentUser);
    }

    private void validateProfileInput(User currentUser, String username, String email) {
        if (username.isBlank()) {
            throw new IllegalArgumentException("Name must not be blank");
        }

        if (username.length() < 3) {
            throw new IllegalArgumentException("Name must be at least 3 characters");
        }

        if (email.isBlank()) {
            throw new IllegalArgumentException("Email must not be blank");
        }

        if (!email.contains("@")) {
            throw new IllegalArgumentException("Email format is invalid");
        }

        if (!username.equalsIgnoreCase(currentUser.getUsername())
                && userRepository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException("Name already exists");
        }

        if (!email.equalsIgnoreCase(currentUser.getEmail())
                && userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email already exists");
        }
    }

    private String toAvatarDataUrl(MultipartFile avatarFile) {
        String contentType = avatarFile.getContentType() == null ? "" : avatarFile.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Avatar must be an image file");
        }

        if (avatarFile.getSize() > MAX_AVATAR_SIZE_BYTES) {
            throw new IllegalArgumentException("Avatar must be 5MB or smaller");
        }

        try {
            String encoded = Base64.getEncoder().encodeToString(avatarFile.getBytes());
            return "data:" + contentType + ";base64," + encoded;
        } catch (IOException e) {
            throw new IllegalArgumentException("Avatar upload could not be processed");
        }
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
