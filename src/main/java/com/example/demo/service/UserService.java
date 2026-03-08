package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import static com.example.demo.controller.AuthController.SESSION_USER_ID;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User getCurrentUser(HttpSession session) {
        Object userIdObj = session.getAttribute(SESSION_USER_ID);

        if (userIdObj == null) {
            return null;
        }

        String userId = String.valueOf(userIdObj);

        return userRepository.findById(userId).orElse(null);
    }

    public User requireCurrentUser(HttpSession session) {
        User user = getCurrentUser(session);

        if (user == null) {
            throw new IllegalStateException("User is not logged in");
        }

        return user;
    }
}