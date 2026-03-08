package com.example.demo.service;

import com.example.demo.entity.Setup;
import com.example.demo.entity.User;
import com.example.demo.repository.SetupRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class SetupService {

    private final SetupRepository repo;

    public SetupService(SetupRepository repo) {
        this.repo = repo;
    }

    public List<Setup> findActiveByUser(String userId) {
        return repo.findByUserIdAndActiveTrueOrderByNameAsc(userId);
    }

    public Setup create(String name, String description, User user) {
        Setup s = new Setup();
        s.setId(UUID.randomUUID().toString());
        s.setName(name);
        s.setDescription(description);
        s.setUser(user);
        s.setActive(true);

        return repo.save(s);
    }

    public Setup findByIdForUser(String id, String userId) {
        return repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Setup not found"));
    }
}