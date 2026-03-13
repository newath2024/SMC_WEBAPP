package com.example.demo.service;

import com.example.demo.entity.PlanType;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    public User register(String username, String email, String rawPassword) {
        String normalizedUsername = normalize(username);
        String normalizedEmail = normalize(email).toLowerCase();

        validateRegisterInput(normalizedUsername, normalizedEmail, rawPassword);

        if (userRepository.existsByUsernameIgnoreCase(normalizedUsername)) {
            throw new IllegalArgumentException("Username already exists");
        }

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new IllegalArgumentException("Email already exists");
        }

        User user = new User();
        user.setUsername(normalizedUsername);
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole("USER");
        user.setPlanType(PlanType.STANDARD);
        user.setActive(true);

        return userRepository.save(user);
    }

    public User login(String usernameOrEmail, String rawPassword) {
        String input = normalize(usernameOrEmail);

        if (input.isBlank()) {
            throw new IllegalArgumentException("Username or email must not be blank");
        }

        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Password must not be blank");
        }

        User user = findByUsernameOrEmail(input);

        if (user == null) {
            throw new IllegalArgumentException("Invalid username/email or password");
        }

        if (!user.isActive()) {
            throw new IllegalArgumentException("Your account is inactive");
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid username/email or password");
        }

        return user;
    }

    private User findByUsernameOrEmail(String input) {
        return userRepository.findByUsernameIgnoreCase(input)
                .or(() -> userRepository.findByEmailIgnoreCase(input.toLowerCase()))
                .orElse(null);
    }

    private void validateRegisterInput(String username, String email, String rawPassword) {
        if (username.isBlank()) {
            throw new IllegalArgumentException("Username must not be blank");
        }

        if (email.isBlank()) {
            throw new IllegalArgumentException("Email must not be blank");
        }

        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Password must not be blank");
        }

        if (username.length() < 3) {
            throw new IllegalArgumentException("Username must be at least 3 characters");
        }

        if (rawPassword.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }

        if (!email.contains("@")) {
            throw new IllegalArgumentException("Email format is invalid");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
