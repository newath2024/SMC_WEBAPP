package com.example.demo.service;

import com.example.demo.entity.PlanType;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${app.admin.bootstrap.email:}")
    private String configuredEmail;

    @Value("${app.admin.bootstrap.username:}")
    private String configuredUsername;

    @Value("${app.admin.bootstrap.password:}")
    private String configuredPassword;

    public AdminBootstrapService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrapConfiguredAdmin() {
        if (!hasConfiguredIdentity()) {
            return;
        }

        User existingUser = findConfiguredUser();
        if (existingUser != null) {
            ensureConfiguredAdmin(existingUser);
            return;
        }

        if (!hasRequiredFieldsForCreation()) {
            log.info("Admin bootstrap is configured for promotion only. Set email, username, and password to auto-create the admin account.");
            return;
        }

        User admin = new User();
        admin.setUsername(normalize(configuredUsername));
        admin.setEmail(normalize(configuredEmail).toLowerCase());
        admin.setPasswordHash(passwordEncoder.encode(configuredPassword));
        admin.setRole("ADMIN");
        admin.setPlanType(PlanType.ADMIN);
        admin.setActive(true);
        userRepository.save(admin);
        log.info("Created bootstrap admin account for {}", maskEmail(admin.getEmail()));
    }

    @Transactional
    public User ensureConfiguredAdmin(User user) {
        if (user == null || !matchesConfiguredIdentity(user)) {
            return user;
        }

        boolean changed = false;
        if (!"ADMIN".equalsIgnoreCase(user.getRole())) {
            user.setRole("ADMIN");
            changed = true;
        }
        if (user.getPlanType() != PlanType.ADMIN) {
            user.setPlanType(PlanType.ADMIN);
            changed = true;
        }
        if (!user.isActive()) {
            user.setActive(true);
            changed = true;
        }

        if (!changed) {
            return user;
        }

        User savedUser = userRepository.save(user);
        log.info("Granted admin access to {}", maskEmail(savedUser.getEmail()));
        return savedUser;
    }

    private User findConfiguredUser() {
        String email = normalize(configuredEmail).toLowerCase();
        if (!email.isBlank()) {
            User userByEmail = userRepository.findByEmailIgnoreCase(email).orElse(null);
            if (userByEmail != null) {
                return userByEmail;
            }
        }

        String username = normalize(configuredUsername);
        if (!username.isBlank()) {
            return userRepository.findByUsernameIgnoreCase(username).orElse(null);
        }

        return null;
    }

    private boolean matchesConfiguredIdentity(User user) {
        String email = normalize(configuredEmail).toLowerCase();
        if (!email.isBlank() && email.equalsIgnoreCase(normalize(user.getEmail()))) {
            return true;
        }

        String username = normalize(configuredUsername);
        return !username.isBlank() && username.equalsIgnoreCase(normalize(user.getUsername()));
    }

    private boolean hasConfiguredIdentity() {
        return !normalize(configuredEmail).isBlank() || !normalize(configuredUsername).isBlank();
    }

    private boolean hasRequiredFieldsForCreation() {
        return !normalize(configuredEmail).isBlank()
                && !normalize(configuredUsername).isBlank()
                && !normalize(configuredPassword).isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String maskEmail(String email) {
        String normalized = normalize(email);
        int atIndex = normalized.indexOf('@');
        if (atIndex <= 1) {
            return normalized;
        }
        return normalized.charAt(0) + "***" + normalized.substring(atIndex);
    }
}
