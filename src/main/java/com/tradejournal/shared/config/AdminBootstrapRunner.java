package com.tradejournal.config;

import com.tradejournal.entity.PlanType;
import com.tradejournal.entity.User;
import com.tradejournal.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UserRepository userRepository;
    private final Environment environment;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AdminBootstrapRunner(UserRepository userRepository, Environment environment) {
        this.userRepository = userRepository;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = normalize(firstNonBlank(
                environment.getProperty("app.admin.bootstrap.email"),
                environment.getProperty("ADMIN_EMAIL")));
        String username = normalize(firstNonBlank(
                environment.getProperty("app.admin.bootstrap.username"),
                environment.getProperty("ADMIN_USERNAME")));
        String password = firstNonBlank(
                environment.getProperty("app.admin.bootstrap.password"),
                environment.getProperty("ADMIN_PASSWORD"));

        if (!StringUtils.hasText(email) && !StringUtils.hasText(username)) {
            return;
        }

        if (!StringUtils.hasText(username) && StringUtils.hasText(email) && email.contains("@")) {
            username = email.substring(0, email.indexOf('@'));
        }

        User user = findExistingUser(email, username);
        if (user != null) {
            promoteExistingUser(user, password);
            return;
        }

        if (!StringUtils.hasText(email) || !StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            log.warn("Admin bootstrap skipped because ADMIN_EMAIL/ADMIN_USERNAME/ADMIN_PASSWORD are incomplete.");
            return;
        }

        createAdminUser(username, email, password);
    }

    private User findExistingUser(String email, String username) {
        if (StringUtils.hasText(email)) {
            User byEmail = userRepository.findByEmailIgnoreCase(email.toLowerCase()).orElse(null);
            if (byEmail != null) {
                return byEmail;
            }
        }

        if (StringUtils.hasText(username)) {
            return userRepository.findByUsernameIgnoreCase(username).orElse(null);
        }

        return null;
    }

    private void promoteExistingUser(User user, String rawPassword) {
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

        if (StringUtils.hasText(rawPassword) && !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            changed = true;
        }

        if (changed) {
            userRepository.save(user);
            log.info("Admin bootstrap promoted existing user [{}] to admin.", user.getEmail());
        } else {
            log.info("Admin bootstrap found existing admin user [{}]. No changes applied.", user.getEmail());
        }
    }

    private void createAdminUser(String username, String email, String rawPassword) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email.toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole("ADMIN");
        user.setPlanType(PlanType.ADMIN);
        user.setActive(true);
        userRepository.save(user);
        log.info("Admin bootstrap created admin user [{}].", user.getEmail());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }
}
