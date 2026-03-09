package com.example.demo.service;

import com.example.demo.entity.Trade;
import com.example.demo.entity.TradeImage;
import com.example.demo.repository.TradeImageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

@Service
public class TradeImageService {

    private static final String IMAGE_URL_PREFIX = "/uploads/trade-images/";
    private static final Path STORAGE_DIR = Path.of("data", "uploads", "trade-images");

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

            String contentType = file.getContentType();
            if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
                throw new IllegalArgumentException("Only image files are allowed");
            }

            String originalName = file.getOriginalFilename();
            String extension = extractExtension(originalName);
            String storedName = UUID.randomUUID() + extension;
            Path target = STORAGE_DIR.resolve(storedName);

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

    public void deleteByTradeId(String tradeId) {
        List<TradeImage> images = tradeImageRepository.findByTradeIdOrderByCreatedAtAsc(tradeId);
        tradeImageRepository.deleteByTradeId(tradeId);

        for (TradeImage image : images) {
            deletePhysicalFile(image.getImageUrl());
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
        if (imageUrl == null || !imageUrl.startsWith(IMAGE_URL_PREFIX)) {
            return;
        }

        String filename = imageUrl.substring(IMAGE_URL_PREFIX.length());
        if (filename.isBlank()) {
            return;
        }

        try {
            Files.deleteIfExists(STORAGE_DIR.resolve(filename));
        } catch (IOException ignored) {
        }
    }

    private String extractExtension(String filename) {
        if (filename == null) {
            return ".jpg";
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot <= -1 || lastDot >= filename.length() - 1) {
            return ".jpg";
        }
        String ext = filename.substring(lastDot).toLowerCase();
        if (ext.length() > 10) {
            return ".jpg";
        }
        return ext;
    }

    private String normalizeImageType(String imageType) {
        if (imageType == null || imageType.isBlank()) {
            return "SETUP";
        }
        String normalized = imageType.trim().toUpperCase();
        return switch (normalized) {
            case "BEFORE", "AFTER", "REVIEW", "SETUP" -> normalized;
            default -> "SETUP";
        };
    }
}
