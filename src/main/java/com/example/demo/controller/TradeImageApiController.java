package com.example.demo.controller;

import com.example.demo.entity.Trade;
import com.example.demo.entity.User;
import com.example.demo.entity.TradeImage;
import com.example.demo.service.TradeImageService;
import com.example.demo.service.TradeService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trades")
public class TradeImageApiController {

    private final TradeService tradeService;
    private final TradeImageService tradeImageService;
    private final UserService userService;

    public TradeImageApiController(
            TradeService tradeService,
            TradeImageService tradeImageService,
            UserService userService
    ) {
        this.tradeService = tradeService;
        this.tradeImageService = tradeImageService;
        this.userService = userService;
    }

    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadImages(
            @PathVariable String id,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "imageType", required = false) String imageType,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized"));
        }

        try {
            Trade trade = userService.isAdmin(currentUser)
                    ? tradeService.findByIdForAdmin(id)
                    : tradeService.findEditableByIdForUser(id, currentUser.getId());

            tradeImageService.saveSetupImages(trade, files, imageType);

            List<TradeImage> images = tradeImageService.findByTradeId(trade.getId());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("message", "Images uploaded");
            payload.put("count", images.size());
            payload.put("images", images.stream().map(TradeImage::getImageUrl).toList());

            return ResponseEntity.ok(payload);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Unable to upload images"));
        }
    }

    @DeleteMapping("/{id}/images/{imageId}")
    public ResponseEntity<?> deleteImage(
            @PathVariable String id,
            @PathVariable String imageId,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized"));
        }

        try {
            Trade trade = userService.isAdmin(currentUser)
                    ? tradeService.findByIdForAdmin(id)
                    : tradeService.findEditableByIdForUser(id, currentUser.getId());

            boolean deleted = tradeImageService.deleteByTradeIdAndImageId(trade.getId(), imageId);
            if (!deleted) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "Image not found"));
            }

            return ResponseEntity.ok(Map.of("message", "Image deleted"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Unable to delete image"));
        }
    }
}
