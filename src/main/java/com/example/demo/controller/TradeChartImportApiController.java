package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.service.TradingViewChartImportService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/trades")
public class TradeChartImportApiController {

    private final TradingViewChartImportService tradingViewChartImportService;
    private final UserService userService;

    public TradeChartImportApiController(
            TradingViewChartImportService tradingViewChartImportService,
            UserService userService
    ) {
        this.tradingViewChartImportService = tradingViewChartImportService;
        this.userService = userService;
    }

    @PostMapping(value = "/import/tradingview-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> analyzeTradingViewImage(
            @RequestParam("file") MultipartFile file,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized"));
        }

        try {
            TradingViewChartImportService.TradeChartAnalysis analysis = tradingViewChartImportService.analyzeChart(file);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("message", "TradingView screenshot analyzed.");
            payload.put("analysis", analysis);
            return ResponseEntity.ok(payload);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("message", ex.getMessage()));
        }
    }
}
