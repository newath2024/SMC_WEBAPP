package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;

import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User getCurrentUser() {

        return userRepository
                .findByUsername("admin")
                .orElseGet(() -> createDefaultAdmin());
    }

    private User createDefaultAdmin() {

        User u = new User();

        u.setUsername("admin");
        u.setEmail("admin@test.com");
        u.setPasswordHash("admin");
        u.setRole("ADMIN");
        u.setActive(true);

        return userRepository.save(u);
    }

}