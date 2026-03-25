package com.tradejournal.ai.controller;

import com.tradejournal.setup.domain.Setup;
import com.tradejournal.auth.domain.User;
import com.tradejournal.trade.service.HoldingTimeResolver;
import com.tradejournal.trade.service.ResultResolver;
import com.tradejournal.setup.service.SetupMatcher;
import com.tradejournal.setup.service.SetupService;
import com.tradejournal.ai.integration.TradingViewChartImportService;
import com.tradejournal.auth.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trades")
public class TradeChartImportApiController {

    private static final Logger log = LoggerFactory.getLogger(TradeChartImportApiController.class);

    private final TradingViewChartImportService tradingViewChartImportService;
    private final HoldingTimeResolver holdingTimeResolver;
    private final ResultResolver resultResolver;
    private final SetupMatcher setupMatcher;
    private final SetupService setupService;
    private final UserService userService;

    public TradeChartImportApiController(
            TradingViewChartImportService tradingViewChartImportService,
            HoldingTimeResolver holdingTimeResolver,
            ResultResolver resultResolver,
            SetupMatcher setupMatcher,
            SetupService setupService,
            UserService userService
    ) {
        this.tradingViewChartImportService = tradingViewChartImportService;
        this.holdingTimeResolver = holdingTimeResolver;
        this.resultResolver = resultResolver;
        this.setupMatcher = setupMatcher;
        this.setupService = setupService;
        this.userService = userService;
    }

    @PostMapping(value = "/import/tradingview-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> analyzeTradingViewImage(
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "file", required = false) MultipartFile singleFile,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized"));
        }

        List<MultipartFile> normalizedFiles = normalizeFiles(files, singleFile);

        try {
            TradingViewChartImportService.TradeChartAnalysis analysis = tradingViewChartImportService.analyzeCharts(normalizedFiles);
            analysis = holdingTimeResolver.resolve(analysis);
            analysis = resultResolver.resolve(analysis);
            analysis = setupMatcher.resolve(analysis, currentUser);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("message", "TradingView screenshot analyzed.");
            payload.put("analysis", analysis);
            payload.put("imageCount", normalizedFiles.size());
            return ResponseEntity.ok(payload);
        } catch (IllegalArgumentException ex) {
            log.info("TradingView screenshot import rejected. userId='{}', files='{}', count={}, reason='{}'.",
                    currentUser.getId(), safeFilenames(normalizedFiles), normalizedFiles.size(), ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (IllegalStateException ex) {
            log.warn("TradingView screenshot import unavailable. userId='{}', files='{}', count={}, reason='{}'.",
                    currentUser.getId(), safeFilenames(normalizedFiles), normalizedFiles.size(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.error("TradingView screenshot import failed. userId='{}', files='{}', count={}.",
                    currentUser.getId(), safeFilenames(normalizedFiles), normalizedFiles.size(), ex);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping(value = "/import/setup-suggestion")
    public ResponseEntity<?> createSetupFromSuggestion(
            @RequestParam("name") String name,
            @RequestParam(value = "description", required = false) String description,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized"));
        }

        if (!userService.hasProAccess(currentUser)
                && setupService.findByUserIncludingArchived(currentUser.getId()).size() >= userService.resolveSetupLimit(currentUser)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Setup limit reached. Upgrade to Pro to create more setups."));
        }

        try {
            Setup setup = setupService.create(name, description, currentUser);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("message", "Setup created from suggestion.");
            payload.put("setupId", setup.getId());
            payload.put("setupName", setup.getName());
            payload.put("setupDescription", setup.getDescription());
            return ResponseEntity.ok(payload);
        } catch (IllegalArgumentException ex) {
            log.info("Setup suggestion create rejected. userId='{}', name='{}', reason='{}'.",
                    currentUser.getId(), name, ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    private List<MultipartFile> normalizeFiles(List<MultipartFile> files, MultipartFile singleFile) {
        List<MultipartFile> normalized = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                if (file != null && !file.isEmpty()) {
                    normalized.add(file);
                }
            }
        }
        if (normalized.isEmpty() && singleFile != null && !singleFile.isEmpty()) {
            normalized.add(singleFile);
        }
        return normalized;
    }

    private String safeFilename(MultipartFile file) {
        if (file == null || !StringUtils.hasText(file.getOriginalFilename())) {
            return "(unnamed)";
        }
        return file.getOriginalFilename().trim();
    }

    private String safeFilenames(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }

        List<String> names = new ArrayList<>();
        for (MultipartFile file : files) {
            names.add(safeFilename(file));
            if (names.size() == 5) {
                break;
            }
        }
        return String.join(", ", names);
    }
}
