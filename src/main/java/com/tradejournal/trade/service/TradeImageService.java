package com.tradejournal.trade.service;

import com.tradejournal.trade.domain.Trade;
import com.tradejournal.trade.domain.TradeImage;
import com.tradejournal.trade.repository.TradeImageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class TradeImageService {

    private static final String IMAGE_URL_PREFIX = "/uploads/trade-images/";
    private static final Path STORAGE_DIR = Path.of("data", "uploads", "trade-images");
    private static final Path STORAGE_ROOT = STORAGE_DIR.toAbsolutePath().normalize();
    private static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024L * 1024L;
    private static final Set<String> ALLOWED_IMAGE_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/webp",
            "image/gif"
    );

    private final TradeImageRepository tradeImageRepository;

    public TradeImageService(TradeImageRepository tradeImageRepository) {
        this.tradeImageRepository = tradeImageRepository;
    }

    public List<TradeImage> findByTradeId(String tradeId) {
        return tradeImageRepository.findByTradeIdOrderByCreatedAtAsc(tradeId);
    }

    public void saveSetupImages(Trade trade, MultipartFile[] files, String imageType) {
        if (trade == null || files == null || files.length == 0) {
            return;
        }

        String resolvedImageType = normalizeImageType(imageType);
        ensureStorageDir();

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }

            String contentType = normalizeContentType(file.getContentType());
            if (!ALLOWED_IMAGE_CONTENT_TYPES.contains(contentType)) {
                throw new IllegalArgumentException("Only PNG, JPG, WEBP, or GIF images are allowed");
            }
            if (file.getSize() > MAX_IMAGE_SIZE_BYTES) {
                throw new IllegalArgumentException("Each image must be 10MB or smaller");
            }

            String originalName = file.getOriginalFilename();
            String extension = resolveStoredExtension(contentType);
            String storedName = UUID.randomUUID() + extension;
            Path target = STORAGE_ROOT.resolve(storedName);

            try {
                Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                throw new RuntimeException("Failed to store image file", ex);
            }

            TradeImage image = new TradeImage();
            image.setTrade(trade);
            image.setImageType(resolvedImageType);
            image.setImageUrl(IMAGE_URL_PREFIX + storedName);
            image.setCaption(originalName != null ? originalName.trim() : null);
            tradeImageRepository.save(image);
        }
    }

    @Transactional
    public void deleteByTradeId(String tradeId) {
        List<TradeImage> images = tradeImageRepository.findByTradeIdOrderByCreatedAtAsc(tradeId);
        tradeImageRepository.deleteByTradeId(tradeId);

        for (TradeImage image : images) {
            deletePhysicalFile(image.getImageUrl());
        }
    }

    @Transactional
    public void deleteByTradeIds(List<String> tradeIds) {
        if (tradeIds == null || tradeIds.isEmpty()) {
            return;
        }

        List<String> normalizedTradeIds = tradeIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (normalizedTradeIds.isEmpty()) {
            return;
        }

        List<TradeImage> images = tradeImageRepository.findByTradeIdIn(normalizedTradeIds);
        tradeImageRepository.deleteByTradeIdIn(normalizedTradeIds);

        LinkedHashSet<String> imageUrls = new LinkedHashSet<>();
        for (TradeImage image : images) {
            if (image != null && StringUtils.hasText(image.getImageUrl())) {
                imageUrls.add(image.getImageUrl().trim());
            }
        }

        for (String imageUrl : imageUrls) {
            deletePhysicalFile(imageUrl);
        }
    }

    @Transactional
    public boolean deleteByTradeIdAndImageId(String tradeId, String imageId) {
        TradeImage image = tradeImageRepository.findByIdAndTradeId(imageId, tradeId).orElse(null);
        if (image == null) {
            return false;
        }

        tradeImageRepository.delete(image);
        deletePhysicalFile(image.getImageUrl());
        return true;
    }

    private void ensureStorageDir() {
        try {
            Files.createDirectories(STORAGE_DIR);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to prepare image storage directory", ex);
        }
    }

    private void deletePhysicalFile(String imageUrl) {
        Path target = resolveStoredImagePath(imageUrl);
        if (target == null) {
            return;
        }

        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
        }
    }

    private Path resolveStoredImagePath(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith(IMAGE_URL_PREFIX)) {
            return null;
        }

        String filename = imageUrl.substring(IMAGE_URL_PREFIX.length()).trim();
        if (filename.isBlank()) {
            return null;
        }

        Path resolved = STORAGE_ROOT.resolve(filename).normalize();
        if (!resolved.startsWith(STORAGE_ROOT)) {
            return null;
        }
        return resolved;
    }

    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "";
        }
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        int separatorIndex = normalized.indexOf(';');
        return separatorIndex >= 0 ? normalized.substring(0, separatorIndex).trim() : normalized;
    }

    private String resolveStoredExtension(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }

    private String normalizeImageType(String imageType) {
        if (imageType == null || imageType.isBlank()) {
            return "SETUP";
        }
        String normalized = imageType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "BEFORE", "AFTER", "REVIEW", "SETUP" -> normalized;
            default -> "SETUP";
        };
    }
}
