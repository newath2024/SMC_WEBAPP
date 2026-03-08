package com.example.demo.service;

import com.example.demo.entity.MistakeTag;
import com.example.demo.repository.MistakeTagRepository;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

@Service
public class MistakeTagService {

    private final MistakeTagRepository mistakeTagRepository;

    public MistakeTagService(MistakeTagRepository mistakeTagRepository) {
        this.mistakeTagRepository = mistakeTagRepository;
    }

    public List<MistakeTag> findActive() {
        return mistakeTagRepository.findByActiveTrueOrderByNameAsc();
    }

    public List<MistakeTag> findAll() {
        return mistakeTagRepository.findAll()
                .stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();
    }

    public List<MistakeTag> findAllByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mistakeTagRepository.findAllById(ids);
    }

    public MistakeTag findById(String id) {
        return mistakeTagRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mistake tag not found: " + id));
    }

    public MistakeTag create(String code, String name, String description) {
        String normalizedCode = normalizeCode(code, name);
        String normalizedName = normalizeName(name);

        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("Mistake name must not be blank");
        }

        if (mistakeTagRepository.existsByCodeIgnoreCase(normalizedCode)) {
            throw new IllegalArgumentException("Mistake code already exists");
        }

        MistakeTag tag = new MistakeTag();
        tag.setCode(normalizedCode);
        tag.setName(normalizedName);
        tag.setDescription(description != null ? description.trim() : null);
        tag.setActive(true);

        return withSqliteBusyRetry(() -> mistakeTagRepository.save(tag));
    }

    public MistakeTag update(String id, String code, String name, String description, boolean active) {
        MistakeTag existing = findById(id);

        String normalizedCode = normalizeCode(code, name);
        String normalizedName = normalizeName(name);

        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("Mistake name must not be blank");
        }

        mistakeTagRepository.findByCodeIgnoreCase(normalizedCode).ifPresent(found -> {
            if (!found.getId().equals(existing.getId())) {
                throw new IllegalArgumentException("Mistake code already exists");
            }
        });

        existing.setCode(normalizedCode);
        existing.setName(normalizedName);
        existing.setDescription(description != null ? description.trim() : null);
        existing.setActive(active);

        return withSqliteBusyRetry(() -> mistakeTagRepository.save(existing));
    }

    public void toggleActive(String id) {
        MistakeTag tag = findById(id);
        tag.setActive(!tag.isActive());
        withSqliteBusyRetry(() -> mistakeTagRepository.save(tag));
    }

    public MistakeTag findOrCreateByName(String rawName) {
        String normalizedName = normalizeName(rawName);
        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("Custom mistake name must not be blank");
        }

        String normalizedCode = normalizeCode(null, normalizedName);

        return mistakeTagRepository.findByCodeIgnoreCase(normalizedCode)
                .orElseGet(() -> {
                    MistakeTag tag = new MistakeTag();
                    tag.setCode(normalizedCode);
                    tag.setName(normalizedName);
                    tag.setDescription("Created from trade form");
                    tag.setActive(true);
                    return withSqliteBusyRetry(() -> mistakeTagRepository.save(tag));
                });
    }

    private <T> T withSqliteBusyRetry(Supplier<T> action) {
        int maxAttempts = 3;
        long waitMillis = 200L;
        CannotAcquireLockException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (CannotAcquireLockException ex) {
                lastError = ex;
                if (attempt == maxAttempts) {
                    throw ex;
                }
                try {
                    Thread.sleep(waitMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }

        throw lastError;
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }

    private String normalizeCode(String code, String fallbackName) {
        String base = (code != null && !code.trim().isBlank()) ? code : fallbackName;
        if (base == null) {
            return "";
        }

        return base.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}
