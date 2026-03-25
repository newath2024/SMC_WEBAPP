package com.tradejournal.setup.service;

import com.tradejournal.setup.domain.Setup;
import com.tradejournal.auth.domain.User;
import com.tradejournal.setup.repository.SetupRepository;
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

    public List<Setup> findByUserIncludingArchived(String userId) {
        return repo.findByUserIdOrderByActiveDescNameAsc(userId);
    }

    public List<Setup> findAllForAdmin() {
        return repo.findAll()
                .stream()
                .sorted((a, b) -> {
                    String aName = a.getName() == null ? "" : a.getName();
                    String bName = b.getName() == null ? "" : b.getName();
                    return aName.compareToIgnoreCase(bName);
                })
                .toList();
    }

    public Setup create(String name, String description, User user) {
        String normalizedName = normalizeName(name);
        if (repo.existsByUserIdAndNameIgnoreCase(user.getId(), normalizedName)) {
            throw new IllegalArgumentException("Setup name already exists");
        }

        Setup s = new Setup();
        s.setId(UUID.randomUUID().toString());
        s.setName(normalizedName);
        s.setDescription(normalizeDescription(description));
        s.setUser(user);
        s.setActive(true);

        return repo.save(s);
    }

    public Setup findByIdForUser(String id, String userId) {
        return repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Setup not found"));
    }

    public Setup findByIdForAdmin(String id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Setup not found"));
    }

    public Setup updateForUser(String id, String userId, String name, String description, boolean active) {
        Setup existing = findByIdForUser(id, userId);
        return applyUpdate(existing, name, description, active);
    }

    public Setup updateForAdmin(String id, String name, String description, boolean active) {
        Setup existing = findByIdForAdmin(id);
        return applyUpdate(existing, name, description, active);
    }

    public void setActiveForUser(String id, String userId, boolean active) {
        Setup existing = findByIdForUser(id, userId);
        existing.setActive(active);
        repo.save(existing);
    }

    public void setActiveForAdmin(String id, boolean active) {
        Setup existing = findByIdForAdmin(id);
        existing.setActive(active);
        repo.save(existing);
    }

    private Setup applyUpdate(Setup existing, String name, String description, boolean active) {
        String normalizedName = normalizeName(name);
        String ownerId = existing.getUser() != null ? existing.getUser().getId() : null;
        if (ownerId != null && repo.existsByUserIdAndNameIgnoreCaseAndIdNot(ownerId, normalizedName, existing.getId())) {
            throw new IllegalArgumentException("Setup name already exists");
        }

        existing.setName(normalizedName);
        existing.setDescription(normalizeDescription(description));
        existing.setActive(active);
        return repo.save(existing);
    }

    private String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Setup name is required");
        }
        return normalized;
    }

    private String normalizeDescription(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
