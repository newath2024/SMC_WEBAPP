package com.tradejournal.mistake.service;

import com.tradejournal.auth.domain.User;
import com.tradejournal.auth.service.UserService;
import com.tradejournal.mistake.domain.MistakeTag;
import com.tradejournal.mistake.repository.MistakeTagRepository;
import com.tradejournal.trade.repository.TradeMistakeTagRepository;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

@Service
public class MistakeTagService {

    private final MistakeTagRepository mistakeTagRepository;
    private final TradeMistakeTagRepository tradeMistakeTagRepository;
    private final UserService userService;

    public MistakeTagService(
            MistakeTagRepository mistakeTagRepository,
            TradeMistakeTagRepository tradeMistakeTagRepository,
            UserService userService
    ) {
        this.mistakeTagRepository = mistakeTagRepository;
        this.tradeMistakeTagRepository = tradeMistakeTagRepository;
        this.userService = userService;
    }

    public List<MistakeTag> findActive() {
        return sortTags(mistakeTagRepository.findByActiveTrueOrderByNameAsc());
    }

    public List<MistakeTag> findActiveForUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            return findActive();
        }
        return sortTags(mistakeTagRepository.findVisibleActiveForUser(userId.trim()));
    }

    public List<MistakeTag> findAllForAdmin() {
        return sortTags(mistakeTagRepository.findAll());
    }

    public List<MistakeTag> findAllVisibleToUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        return sortTags(mistakeTagRepository.findVisibleForUser(userId.trim()));
    }

    public List<MistakeTag> findAllVisibleByIds(String userId, List<String> ids) {
        if (!StringUtils.hasText(userId) || ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mistakeTagRepository.findVisibleByIdIn(userId.trim(), ids);
    }

    public long countOwnedByUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            return 0L;
        }
        return mistakeTagRepository.countByUserId(userId.trim());
    }

    public MistakeTag findByIdForAdmin(String id) {
        return mistakeTagRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Mistake tag not found: " + id));
    }

    public MistakeTag findByIdForUser(String id, String userId) {
        return mistakeTagRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("Mistake tag not found: " + id));
    }

    @Transactional
    public MistakeTag createGlobal(String code, String name, String description) {
        String normalizedName = normalizeName(name);
        String normalizedCode = normalizeCode(code, normalizedName);

        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("Mistake name must not be blank");
        }
        if (mistakeTagRepository.existsByCodeIgnoreCase(normalizedCode)) {
            throw new IllegalArgumentException("Mistake code already exists");
        }

        MistakeTag tag = new MistakeTag();
        tag.setCode(normalizedCode);
        tag.setName(normalizedName);
        tag.setDescription(normalizeDescription(description));
        tag.setActive(true);
        tag.setUser(null);
        return withSqliteBusyRetry(() -> mistakeTagRepository.save(tag));
    }

    @Transactional
    public MistakeTag createForUser(String code, String name, String description, User user) {
        if (user == null) {
            throw new IllegalArgumentException("User is required");
        }

        String normalizedName = normalizeName(name);
        String normalizedCode = resolveCodeForUserCreate(code, normalizedName, user);
        enforceOwnedTagLimit(user);

        MistakeTag tag = new MistakeTag();
        tag.setCode(normalizedCode);
        tag.setName(normalizedName);
        tag.setDescription(normalizeDescription(description));
        tag.setActive(true);
        tag.setUser(user);
        return withSqliteBusyRetry(() -> mistakeTagRepository.save(tag));
    }

    @Transactional
    public MistakeTag updateGlobal(String id, String code, String name, String description, boolean active) {
        MistakeTag existing = findByIdForAdmin(id);
        String normalizedName = normalizeName(name);
        String normalizedCode = normalizeCode(code, normalizedName);

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
        existing.setDescription(normalizeDescription(description));
        existing.setActive(active);
        existing.setUser(null);
        return withSqliteBusyRetry(() -> mistakeTagRepository.save(existing));
    }

    @Transactional
    public MistakeTag updateForUser(String id, String code, String name, String description, boolean active, String userId) {
        MistakeTag existing = findByIdForUser(id, userId);
        String normalizedName = normalizeName(name);
        String normalizedCode = normalizeCode(code, normalizedName);

        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("Mistake name must not be blank");
        }

        validateVisibleCodeConflict(userId, normalizedCode, existing.getId());
        if (!normalizedCode.equalsIgnoreCase(existing.getCode()) && mistakeTagRepository.existsByCodeIgnoreCase(normalizedCode)) {
            throw new IllegalArgumentException("Mistake code already exists");
        }

        existing.setCode(normalizedCode);
        existing.setName(normalizedName);
        existing.setDescription(normalizeDescription(description));
        existing.setActive(active);
        return withSqliteBusyRetry(() -> mistakeTagRepository.save(existing));
    }

    @Transactional
    public void toggleActiveGlobal(String id) {
        MistakeTag tag = findByIdForAdmin(id);
        tag.setActive(!tag.isActive());
        withSqliteBusyRetry(() -> mistakeTagRepository.save(tag));
    }

    @Transactional
    public void toggleActiveForUser(String id, String userId) {
        MistakeTag tag = findByIdForUser(id, userId);
        tag.setActive(!tag.isActive());
        withSqliteBusyRetry(() -> mistakeTagRepository.save(tag));
    }

    @Transactional
    public void deleteGlobal(String id) {
        findByIdForAdmin(id);
        withSqliteBusyRetry(() -> {
            tradeMistakeTagRepository.deleteByMistakeTagId(id);
            mistakeTagRepository.deleteById(id);
            return null;
        });
    }

    @Transactional
    public void deleteForUser(String id, String userId) {
        findByIdForUser(id, userId);
        withSqliteBusyRetry(() -> {
            tradeMistakeTagRepository.deleteByMistakeTagId(id);
            mistakeTagRepository.deleteById(id);
            return null;
        });
    }

    @Transactional
    public MistakeTag findOrCreateByName(String rawName, User user) {
        String normalizedName = normalizeName(rawName);
        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("Custom mistake name must not be blank");
        }

        String normalizedCode = normalizeCode(null, normalizedName);
        MistakeTag globalTag = mistakeTagRepository.findByCodeIgnoreCaseAndUserIsNull(normalizedCode).orElse(null);
        if (globalTag != null) {
            return globalTag;
        }

        if (user == null || userService.isAdmin(user)) {
            MistakeTag existing = mistakeTagRepository.findByCodeIgnoreCase(normalizedCode).orElse(null);
            if (existing != null) {
                return existing;
            }
            MistakeTag tag = new MistakeTag();
            tag.setCode(normalizedCode);
            tag.setName(normalizedName);
            tag.setDescription("Created from trade form");
            tag.setActive(true);
            tag.setUser(null);
            return withSqliteBusyRetry(() -> mistakeTagRepository.save(tag));
        }

        MistakeTag ownedTag = mistakeTagRepository.findByCodeIgnoreCaseAndUserId(normalizedCode, user.getId()).orElse(null);
        if (ownedTag != null) {
            return ownedTag;
        }

        enforceOwnedTagLimit(user);
        MistakeTag tag = new MistakeTag();
        tag.setCode(resolveUniqueOwnedCode(normalizedCode, user.getId()));
        tag.setName(normalizedName);
        tag.setDescription("Created from trade form");
        tag.setActive(true);
        tag.setUser(user);
        return withSqliteBusyRetry(() -> mistakeTagRepository.save(tag));
    }

    private void validateVisibleCodeConflict(String userId, String normalizedCode, String excludedId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(normalizedCode)) {
            return;
        }

        for (MistakeTag tag : mistakeTagRepository.findVisibleForUser(userId.trim())) {
            if (!normalizedCode.equalsIgnoreCase(tag.getCode())) {
                continue;
            }
            if (excludedId != null && excludedId.equals(tag.getId())) {
                continue;
            }
            throw new IllegalArgumentException("Mistake code already exists");
        }
    }

    private String resolveCodeForUserCreate(String code, String normalizedName, User user) {
        String normalizedCode = normalizeCode(code, normalizedName);
        validateVisibleCodeConflict(user.getId(), normalizedCode, null);
        if (!mistakeTagRepository.existsByCodeIgnoreCase(normalizedCode)) {
            return normalizedCode;
        }
        if (StringUtils.hasText(code)) {
            throw new IllegalArgumentException("Mistake code already exists");
        }
        return resolveUniqueOwnedCode(normalizedCode, user.getId());
    }

    private String resolveUniqueOwnedCode(String baseCode, String userId) {
        String safeBase = normalizeCode(baseCode, baseCode);
        if (!mistakeTagRepository.existsByCodeIgnoreCase(safeBase)) {
            return safeBase;
        }

        String userToken = extractUserToken(userId);
        String candidate = safeBase + "_" + userToken;
        if (!mistakeTagRepository.existsByCodeIgnoreCase(candidate)) {
            return candidate;
        }

        for (int suffix = 2; suffix <= 99; suffix++) {
            candidate = safeBase + "_" + userToken + "_" + suffix;
            if (!mistakeTagRepository.existsByCodeIgnoreCase(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException("Could not generate a unique mistake code. Please choose a custom code.");
    }

    private String extractUserToken(String userId) {
        String compact = userId == null ? "" : userId.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (compact.length() >= 4) {
            return compact.substring(0, 4);
        }
        if (compact.isEmpty()) {
            return "USER";
        }
        return compact;
    }

    private void enforceOwnedTagLimit(User user) {
        if (user == null || userService.isAdmin(user) || userService.hasProAccess(user)) {
            return;
        }
        long ownedTagCount = countOwnedByUser(user.getId());
        if (ownedTagCount >= userService.resolveMistakeTagLimit(user)) {
            throw new IllegalStateException("Standard plan includes up to 10 custom mistake tags. Upgrade to Pro for more.");
        }
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }

    private String normalizeDescription(String description) {
        if (!StringUtils.hasText(description)) {
            return null;
        }
        String normalized = description.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeCode(String code, String fallbackName) {
        String base = StringUtils.hasText(code) ? code : fallbackName;
        if (base == null) {
            return "";
        }
        return base.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private List<MistakeTag> sortTags(List<MistakeTag> tags) {
        return tags.stream()
                .sorted((left, right) -> {
                    String leftName = left.getName() == null ? "" : left.getName();
                    String rightName = right.getName() == null ? "" : right.getName();
                    int nameCompare = leftName.compareToIgnoreCase(rightName);
                    if (nameCompare != 0) {
                        return nameCompare;
                    }
                    String leftCode = left.getCode() == null ? "" : left.getCode();
                    String rightCode = right.getCode() == null ? "" : right.getCode();
                    return leftCode.compareToIgnoreCase(rightCode);
                })
                .toList();
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

        throw new IllegalStateException("Retry loop exited without result", lastError);
    }
}
