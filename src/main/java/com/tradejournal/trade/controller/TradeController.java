package com.tradejournal.trade.controller;

import com.tradejournal.trade.domain.Trade;
import com.tradejournal.trade.domain.TradeReview;
import com.tradejournal.auth.domain.User;
import com.tradejournal.setup.domain.Setup;
import com.tradejournal.trade.repository.TradeReviewRepository;
import com.tradejournal.mistake.service.MistakeTagService;
import com.tradejournal.trade.service.TradeImportService;
import com.tradejournal.trade.service.TradeImageService;
import com.tradejournal.trade.service.TradeReviewService;
import com.tradejournal.setup.service.SetupService;
import com.tradejournal.ai.service.AiTradeReviewService;
import com.tradejournal.trade.dto.TradeFilterCriteria;
import com.tradejournal.ai.integration.TradingViewChartImportService;
import com.tradejournal.trade.service.TradeService;
import com.tradejournal.auth.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/trades")
public class TradeController {

    private static final String MT5_IMPORT_PREVIEW_SESSION_KEY = "mt5ImportPreview";

    private final TradeService tradeService;
    private final UserService userService;
    private final SetupService setupService;
    private final MistakeTagService mistakeTagService;
    private final TradeImageService tradeImageService;
    private final TradeReviewService tradeReviewService;
    private final TradeReviewRepository tradeReviewRepository;
    private final TradeImportService mt5ImportService;
    private final TradingViewChartImportService tradingViewChartImportService;
    private final AiTradeReviewService tradeAiReviewService;

    public TradeController(
            TradeService tradeService,
            UserService userService,
            SetupService setupService,
            MistakeTagService mistakeTagService,
            TradeImageService tradeImageService,
            TradeReviewService tradeReviewService,
            TradeReviewRepository tradeReviewRepository,
            TradeImportService mt5ImportService,
            TradingViewChartImportService tradingViewChartImportService,
            AiTradeReviewService tradeAiReviewService
    ) {
        this.tradeService = tradeService;
        this.userService = userService;
        this.setupService = setupService;
        this.mistakeTagService = mistakeTagService;
        this.tradeImageService = tradeImageService;
        this.tradeReviewService = tradeReviewService;
        this.tradeReviewRepository = tradeReviewRepository;
        this.mt5ImportService = mt5ImportService;
        this.tradingViewChartImportService = tradingViewChartImportService;
        this.tradeAiReviewService = tradeAiReviewService;
    }

    private TradeImportService.ImportPreview getStoredImportPreview(HttpSession session, User currentUser) {
        Object attribute = session.getAttribute(MT5_IMPORT_PREVIEW_SESSION_KEY);
        if (!(attribute instanceof TradeImportService.ImportPreview preview)) {
            return null;
        }
        if (currentUser == null || !Objects.equals(preview.userId(), currentUser.getId())) {
            session.removeAttribute(MT5_IMPORT_PREVIEW_SESSION_KEY);
            return null;
        }
        return preview;
    }

    private void storeImportPreview(HttpSession session, TradeImportService.ImportPreview preview) {
        session.setAttribute(MT5_IMPORT_PREVIEW_SESSION_KEY, preview);
    }

    private void clearImportPreview(HttpSession session) {
        session.removeAttribute(MT5_IMPORT_PREVIEW_SESSION_KEY);
    }

    private void fillTradeFormData(Model model, User currentUser) {
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("setups", setupService.findActiveByUser(currentUser.getId()));
        model.addAttribute("mistakeTags", mistakeTagService.findActive());
        model.addAttribute("tradeUsage", Math.min(tradeService.findAllByUser(currentUser.getId()).size(), userService.resolveTradeLimit(currentUser)));
        model.addAttribute("tradeUsageLimit", userService.hasProAccess(currentUser) ? 0 : userService.resolveTradeLimit(currentUser));
        model.addAttribute("hasProAccess", userService.hasProAccess(currentUser));
        model.addAttribute("tradeChartImportConfigured", tradingViewChartImportService.isConfigured());
    }

    private void fillTradeCreatePageData(Model model, User currentUser, HttpSession session) {
        fillTradeFormData(model, currentUser);
        model.addAttribute("mt5ImportPreview", getStoredImportPreview(session, currentUser));
    }

    private void fillTradeListData(
            Model model,
            User currentUser,
            List<Trade> trades,
            boolean filteredView,
            boolean tableOnlyView,
            boolean aiReviewedView,
            boolean adminView,
            String setup,
            String sessionFilter,
            String symbol,
            LocalDate from,
            LocalDate to,
            boolean hasAnyAiTradeReviews,
            String resetTradesUrl,
            AiReviewedTradeListData aiReviewedTradeListData,
            String allTradesViewUrl,
            String aiTradesViewUrl
    ) {
        long winCount = trades.stream()
                .map(Trade::getResult)
                .filter(Objects::nonNull)
                .filter("WIN"::equalsIgnoreCase)
                .count();
        double winRate = trades.isEmpty() ? 0.0 : (winCount * 100.0) / trades.size();
        double totalPnl = trades.stream().mapToDouble(Trade::getPnl).sum();
        long knownRCount = trades.stream().filter(Trade::hasKnownRMultiple).count();
        double averageR = knownRCount == 0 ? 0.0 : trades.stream()
                .filter(Trade::hasKnownRMultiple)
                .mapToDouble(Trade::getRMultiple)
                .average()
                .orElse(0.0);

        model.addAttribute("trades", trades);
        model.addAttribute("filteredView", filteredView);
        model.addAttribute("tableOnlyView", tableOnlyView);
        model.addAttribute("aiReviewedView", aiReviewedView);
        model.addAttribute("adminView", adminView);
        model.addAttribute("selectedSetup", setup);
        model.addAttribute("selectedSession", sessionFilter);
        model.addAttribute("selectedSymbol", symbol);
        model.addAttribute("selectedFrom", from);
        model.addAttribute("selectedTo", to);
        model.addAttribute("tradeCount", trades.size());
        model.addAttribute("knownRTradeCount", knownRCount);
        model.addAttribute("winCount", winCount);
        model.addAttribute("winRate", winRate);
        model.addAttribute("totalPnl", totalPnl);
        model.addAttribute("averageR", averageR);
        model.addAttribute("hasAnyAiTradeReviews", hasAnyAiTradeReviews);
        model.addAttribute("resetTradesUrl", resetTradesUrl);
        model.addAttribute("allTradesViewUrl", allTradesViewUrl);
        model.addAttribute("aiTradesViewUrl", aiTradesViewUrl);
        model.addAttribute("tradeProcessScores", aiReviewedTradeListData.processScores());
        model.addAttribute("tradeAiProcessScores", aiReviewedTradeListData.aiProcessScores());
        model.addAttribute("tradeAiClassifications", aiReviewedTradeListData.classifications());
        model.addAttribute("tradeAiClassificationClasses", aiReviewedTradeListData.classificationClasses());
        model.addAttribute("tradeAiMistakeTags", aiReviewedTradeListData.mistakeTags());
        model.addAttribute("tradeAiReviewTimestamps", aiReviewedTradeListData.reviewTimestamps());
        model.addAttribute("tradeFilterMistakeValues", aiReviewedTradeListData.filterMistakeValues());
        fillTradeFormData(model, currentUser);
    }

    @GetMapping
    public String list(
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "setup", required = false) String setup,
            @RequestParam(value = "session", required = false) String sessionFilter,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "result", required = false) String resultFilter,
            @RequestParam(value = "mistake", required = false) String mistakeFilter,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Model model,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        TradeFilterCriteria criteria = normalizeTradeFilterCriteria(new TradeFilterCriteria(
                view,
                setup,
                sessionFilter,
                symbol,
                resultFilter,
                mistakeFilter,
                from,
                to
        ));

        List<Trade> trades = tradeService.findFilteredByUser(currentUser.getId(), criteria);
        boolean aiReviewedView = criteria.aiReviewedOnly();
        boolean tableOnlyView = aiReviewedView || "table".equalsIgnoreCase(view);
        boolean filteredView = tableOnlyView || criteria.hasActiveFilters();
        boolean adminView = userService.isAdmin(currentUser);
        boolean hasAnyAiTradeReviews = tradeReviewRepository
                .findTopByTradeUserIdAndQualityScoreIsNotNullOrderByUpdatedAtDesc(currentUser.getId())
                .isPresent();
        AiReviewedTradeListData aiReviewedTradeListData = buildAiReviewedTradeListData(trades);
        String allTradesViewUrl = buildTradeListUrl(null, criteria.setup(), criteria.session(), criteria.symbol(), criteria.result(), criteria.mistake(), criteria.from(), criteria.to());
        String aiTradesViewUrl = buildTradeListUrl("ai-reviewed", criteria.setup(), criteria.session(), criteria.symbol(), criteria.result(), criteria.mistake(), criteria.from(), criteria.to());

        if (aiReviewedView) {
            trades = aiReviewedTradeListData.trades();
        }

        fillTradeListData(
                model,
                currentUser,
                trades,
                filteredView,
                tableOnlyView,
                aiReviewedView,
                adminView,
                criteria.setup(),
                criteria.session(),
                criteria.symbol(),
                criteria.from(),
                criteria.to(),
                hasAnyAiTradeReviews,
                aiReviewedView ? "/trades?view=ai-reviewed" : "/trades",
                aiReviewedTradeListData,
                allTradesViewUrl,
                aiTradesViewUrl
        );

        return "trades";
    }

    @GetMapping("/new")
    public String createForm(Model model, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }
        if (userService.isAdmin(currentUser)) {
            return "redirect:/trades";
        }

        model.addAttribute("trade", new Trade());
        model.addAttribute("editMode", false);
        if (!userService.hasProAccess(currentUser) && tradeService.findAllByUser(currentUser.getId()).size() >= userService.resolveTradeLimit(currentUser)) {
            model.addAttribute("error", "You reached the free plan limit (100 trades). Upgrade to Pro for unlimited trade tracking.");
        }
        fillTradeCreatePageData(model, currentUser, session);
        return "tradeForm";
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String importMt5History(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "setupName", required = false) String setupName,
            @RequestParam(value = "defaultHtf", required = false) String defaultHtf,
            @RequestParam(value = "defaultLtf", required = false) String defaultLtf,
            @RequestParam(value = "sessionMode", required = false) String sessionMode,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }
        if (userService.isAdmin(currentUser)) {
            return "redirect:/trades";
        }

        try {
            TradeImportService.ImportPreview preview = mt5ImportService.previewWorkbook(
                    file,
                    currentUser,
                    new TradeImportService.ImportOptions(setupName, defaultHtf, defaultLtf, sessionMode)
            );
            storeImportPreview(session, preview);
            redirectAttributes.addFlashAttribute("importInfo", "Preview ready. Review the parsed trades below, then confirm to import.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            clearImportPreview(session);
            redirectAttributes.addFlashAttribute("importError", ex.getMessage());
        }

        return "redirect:/trades/new";
    }

    @PostMapping("/import/confirm")
    public String confirmMt5Import(
            @RequestParam("previewId") String previewId,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }
        if (userService.isAdmin(currentUser)) {
            return "redirect:/trades";
        }

        TradeImportService.ImportPreview preview = getStoredImportPreview(session, currentUser);
        if (preview == null) {
            redirectAttributes.addFlashAttribute("importError", "No MT5 import preview is available. Please upload the file again.");
            return "redirect:/trades/new";
        }
        if (!Objects.equals(preview.previewId(), previewId)) {
            redirectAttributes.addFlashAttribute("importError", "This preview is outdated. Please review the latest MT5 preview before confirming.");
            return "redirect:/trades/new";
        }

        try {
            TradeImportService.ImportResult result = mt5ImportService.confirmImport(currentUser, preview);
            clearImportPreview(session);

            String successMessage = "Imported " + result.importedCount()
                    + " trades into setup '" + result.setupName()
                    + "' from account '" + result.accountLabel()
                    + "'. Duplicates skipped: " + result.duplicateCount()
                    + ". Unusable rows skipped: " + result.skippedCount() + ".";
            redirectAttributes.addFlashAttribute("importSuccess", successMessage);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("importError", ex.getMessage());
            return "redirect:/trades/new";
        }

        return "redirect:/trades";
    }

    @PostMapping("/import/cancel")
    public String cancelMt5Import(
            @RequestParam("previewId") String previewId,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }
        if (userService.isAdmin(currentUser)) {
            return "redirect:/trades";
        }

        TradeImportService.ImportPreview preview = getStoredImportPreview(session, currentUser);
        if (preview != null && Objects.equals(preview.previewId(), previewId)) {
            clearImportPreview(session);
            redirectAttributes.addFlashAttribute("importInfo", "MT5 import preview cleared.");
        }

        return "redirect:/trades/new";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("trade") Trade trade,
            BindingResult bindingResult,
            @RequestParam(value = "setupId", required = false) String setupId,
            @RequestParam(value = "mistakeIds", required = false) List<String> mistakeIds,
            @RequestParam(value = "customMistakes", required = false) String customMistakes,
            Model model,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }
        if (userService.isAdmin(currentUser)) {
            return "redirect:/admin";
        }
        if (!userService.hasProAccess(currentUser) && tradeService.findAllByUser(currentUser.getId()).size() >= userService.resolveTradeLimit(currentUser)) {
            model.addAttribute("editMode", false);
            fillTradeCreatePageData(model, currentUser, session);
            model.addAttribute("trade", trade);
            model.addAttribute("error", "You reached the free plan limit (100 trades). Upgrade to Pro for unlimited trade tracking.");
            return "tradeForm";
        }

        applySubmittedSetup(trade, setupId);
        if (trade.getSetup() == null || trade.getSetup().getId() == null || trade.getSetup().getId().isBlank()) {
            bindingResult.rejectValue("setup", "required", "Setup is required");
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("editMode", false);
            fillTradeCreatePageData(model, currentUser, session);
            model.addAttribute("trade", trade);
            model.addAttribute("error", buildFormErrorSummary(bindingResult));
            return "tradeForm";
        }

        try {
            tradeService.saveForUser(trade, currentUser, mistakeIds, customMistakes);
            return "redirect:/trades";
        } catch (RuntimeException ex) {
            model.addAttribute("editMode", false);
            fillTradeCreatePageData(model, currentUser, session);
            model.addAttribute("trade", trade);
            model.addAttribute("error", friendlyErrorMessage(ex));
            return "tradeForm";
        }
    }

    @GetMapping("/{id}")
    public String detail(
            @PathVariable String id,
            Model model,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        Trade trade = userService.isAdmin(currentUser)
                ? tradeService.findByIdForAdmin(id)
                : tradeService.findByIdForUser(id, currentUser.getId());

        populateTradeDetailModel(model, currentUser, trade, null);
        return "tradeDetail";
    }

    @PostMapping("/{id}/review")
    public String saveReview(
            @PathVariable String id,
            @ModelAttribute("review") TradeReview reviewForm,
            Model model,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        Trade trade = userService.isAdmin(currentUser)
                ? tradeService.findByIdForAdmin(id)
                : tradeService.findByIdForUser(id, currentUser.getId());

        try {
            TradeReview saved = tradeReviewService.upsertForTrade(trade, reviewForm);
            populateTradeDetailModel(model, currentUser, trade, saved);
            model.addAttribute("reviewSuccess", "Review saved");
        } catch (RuntimeException ex) {
            populateTradeDetailModel(model, currentUser, trade, reviewForm);
            model.addAttribute("reviewError", friendlyErrorMessage(ex));
        }

        return "tradeDetail";
    }

    @PostMapping("/{id}/ai-review/generate")
    public String generateAiReview(
            @PathVariable String id,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        Trade trade = userService.isAdmin(currentUser)
                ? tradeService.findByIdForAdmin(id)
                : tradeService.findByIdForUser(id, currentUser.getId());

        TradeReview review = tradeReviewService.getOrInitByTrade(trade);
        boolean hadExistingAiReview = review.hasAiReview();

        try {
            tradeAiReviewService.generateForTrade(trade, review, tradeImageService.findByTradeId(trade.getId()));
            redirectAttributes.addFlashAttribute("aiReviewNotice", hadExistingAiReview
                    ? "AI Review regenerated."
                    : "AI Review generated.");
        } catch (RuntimeException ex) {
            if (hadExistingAiReview) {
                redirectAttributes.addFlashAttribute("aiReviewNoticeError", friendlyErrorMessage(ex));
            } else {
                redirectAttributes.addFlashAttribute("aiReviewState", "error");
                redirectAttributes.addFlashAttribute("aiReviewError", friendlyErrorMessage(ex));
            }
        }

        return "redirect:/trades/" + id;
    }

    @GetMapping("/{id}/edit")
    public String editForm(
            @PathVariable String id,
            Model model,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        Trade trade = userService.isAdmin(currentUser)
                ? tradeService.findByIdForAdmin(id)
                : tradeService.findEditableByIdForUser(id, currentUser.getId());

        model.addAttribute("trade", trade);
        model.addAttribute("editMode", true);
        model.addAttribute("tradeImages", tradeImageService.findByTradeId(trade.getId()));
        fillTradeFormData(model, currentUser);

        return "tradeForm";
    }

    @PostMapping("/{id}/edit")
    public String update(
            @PathVariable String id,
            @Valid @ModelAttribute("trade") Trade trade,
            BindingResult bindingResult,
            @RequestParam(value = "setupId", required = false) String setupId,
            @RequestParam(value = "mistakeIds", required = false) List<String> mistakeIds,
            @RequestParam(value = "customMistakes", required = false) String customMistakes,
            Model model,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        applySubmittedSetup(trade, setupId);
        if (trade.getSetup() == null || trade.getSetup().getId() == null || trade.getSetup().getId().isBlank()) {
            bindingResult.rejectValue("setup", "required", "Setup is required");
        }

        if (bindingResult.hasErrors()) {
            trade.setId(id);
            model.addAttribute("trade", trade);
            model.addAttribute("editMode", true);
            model.addAttribute("tradeImages", tradeImageService.findByTradeId(id));
            fillTradeFormData(model, currentUser);
            model.addAttribute("error", buildFormErrorSummary(bindingResult));
            return "tradeForm";
        }

        try {
            if (userService.isAdmin(currentUser)) {
                tradeService.updateForAdmin(id, trade, mistakeIds, customMistakes);
            } else {
                tradeService.updateForUser(id, trade, currentUser, mistakeIds, customMistakes);
            }
            return "redirect:/trades/" + id;
        } catch (RuntimeException ex) {
            trade.setId(id);
            model.addAttribute("trade", trade);
            model.addAttribute("editMode", true);
            model.addAttribute("tradeImages", tradeImageService.findByTradeId(id));
            fillTradeFormData(model, currentUser);
            model.addAttribute("error", friendlyErrorMessage(ex));
            return "tradeForm";
        }
    }

    @PostMapping("/{id}/delete")
    public Object delete(
            @PathVariable String id,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return buildDeleteUnauthenticatedResponse(request);
        }

        try {
            if (userService.isAdmin(currentUser)) {
                tradeImageService.deleteByTradeId(id);
                tradeService.deleteForAdmin(id);
            } else {
                tradeImageService.deleteByTradeId(id);
                tradeService.deleteForUser(id, currentUser.getId());
            }
        } catch (RuntimeException ex) {
            return buildDeleteErrorResponse(
                    request,
                    "redirect:/trades",
                    friendlyErrorMessage(ex),
                    redirectAttributes,
                    HttpStatus.BAD_REQUEST
            );
        }

        return buildDeleteSuccessResponse(
                request,
                "redirect:/trades",
                List.of(id),
                "Deleted 1 trade.",
                redirectAttributes
        );
    }

    @PostMapping("/delete-selected")
    public Object deleteSelected(
            @RequestParam(value = "tradeIds", required = false) List<String> tradeIds,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "setup", required = false) String setup,
            @RequestParam(value = "session", required = false) String sessionFilter,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "result", required = false) String resultFilter,
            @RequestParam(value = "mistake", required = false) String mistakeFilter,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return buildDeleteUnauthenticatedResponse(request);
        }

        TradeFilterCriteria criteria = normalizeTradeFilterCriteria(new TradeFilterCriteria(
                view,
                setup,
                sessionFilter,
                symbol,
                resultFilter,
                mistakeFilter,
                from,
                to
        ));
        String redirectUrl = redirectToTradeList(criteria);
        List<String> ownedTradeIds = tradeService.findOwnedTradeIds(currentUser.getId(), tradeIds);
        if (ownedTradeIds.isEmpty()) {
            return buildDeleteErrorResponse(
                    request,
                    redirectUrl,
                    "Select at least one trade to delete.",
                    redirectAttributes,
                    HttpStatus.BAD_REQUEST
            );
        }

        try {
            tradeImageService.deleteByTradeIds(ownedTradeIds);
            int deletedCount = tradeService.deleteForUserIds(ownedTradeIds, currentUser.getId());
            if (deletedCount <= 0) {
                return buildDeleteErrorResponse(
                        request,
                        redirectUrl,
                        "No selected trades could be deleted.",
                        redirectAttributes,
                        HttpStatus.BAD_REQUEST
                );
            }

            return buildDeleteSuccessResponse(
                    request,
                    redirectUrl,
                    ownedTradeIds,
                    deletedCount == 1 ? "Deleted 1 selected trade." : "Deleted " + deletedCount + " selected trades.",
                    redirectAttributes
            );
        } catch (RuntimeException ex) {
            return buildDeleteErrorResponse(
                    request,
                    redirectUrl,
                    friendlyErrorMessage(ex),
                    redirectAttributes,
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    @PostMapping("/delete-filtered")
    public Object deleteFiltered(
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "setup", required = false) String setup,
            @RequestParam(value = "session", required = false) String sessionFilter,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "result", required = false) String resultFilter,
            @RequestParam(value = "mistake", required = false) String mistakeFilter,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return buildDeleteUnauthenticatedResponse(request);
        }

        TradeFilterCriteria criteria = normalizeTradeFilterCriteria(new TradeFilterCriteria(
                view,
                setup,
                sessionFilter,
                symbol,
                resultFilter,
                mistakeFilter,
                from,
                to
        ));
        String redirectUrl = redirectToTradeList(criteria);
        List<String> filteredTradeIds = tradeService.findFilteredTradeIdsForUser(currentUser.getId(), criteria);
        if (filteredTradeIds.isEmpty()) {
            return buildDeleteErrorResponse(
                    request,
                    redirectUrl,
                    "No trades match the current filters.",
                    redirectAttributes,
                    HttpStatus.BAD_REQUEST
            );
        }

        try {
            tradeImageService.deleteByTradeIds(filteredTradeIds);
            int deletedCount = tradeService.deleteForUserIds(filteredTradeIds, currentUser.getId());
            if (deletedCount <= 0) {
                return buildDeleteErrorResponse(
                        request,
                        redirectUrl,
                        "No filtered trades could be deleted.",
                        redirectAttributes,
                        HttpStatus.BAD_REQUEST
                );
            }

            return buildDeleteSuccessResponse(
                    request,
                    redirectUrl,
                    filteredTradeIds,
                    deletedCount == 1 ? "Deleted 1 filtered trade." : "Deleted " + deletedCount + " filtered trades.",
                    redirectAttributes
            );
        } catch (RuntimeException ex) {
            return buildDeleteErrorResponse(
                    request,
                    redirectUrl,
                    friendlyErrorMessage(ex),
                    redirectAttributes,
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private String friendlyErrorMessage(RuntimeException ex) {
        if (isLockError(ex)) {
            return "Database is busy. Please try saving again in a few seconds.";
        }
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "Unable to save trade. Please check input and try again.";
        }
        return message;
    }

    private boolean isLockError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof CannotAcquireLockException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("SQLITE_BUSY")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String buildTradeListUrl(
            String view,
            String setup,
            String sessionFilter,
            String symbol,
            String resultFilter,
            String mistakeFilter,
            LocalDate from,
            LocalDate to
    ) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/trades");
        if (view != null && !view.isBlank()) {
            builder.queryParam("view", view);
        }
        if (setup != null && !setup.isBlank()) {
            builder.queryParam("setup", setup);
        }
        if (sessionFilter != null && !sessionFilter.isBlank()) {
            builder.queryParam("session", sessionFilter);
        }
        if (symbol != null && !symbol.isBlank()) {
            builder.queryParam("symbol", symbol);
        }
        if (resultFilter != null && !resultFilter.isBlank()) {
            builder.queryParam("result", resultFilter);
        }
        if (mistakeFilter != null && !mistakeFilter.isBlank()) {
            builder.queryParam("mistake", mistakeFilter);
        }
        if (from != null) {
            builder.queryParam("from", from);
        }
        if (to != null) {
            builder.queryParam("to", to);
        }
        return builder.toUriString();
    }

    private String redirectToTradeList(TradeFilterCriteria criteria) {
        TradeFilterCriteria safeCriteria = normalizeTradeFilterCriteria(criteria);
        return "redirect:" + buildTradeListUrl(
                safeCriteria.aiReviewedOnly() ? "ai-reviewed" : null,
                safeCriteria.setup(),
                safeCriteria.session(),
                safeCriteria.symbol(),
                safeCriteria.result(),
                safeCriteria.mistake(),
                safeCriteria.from(),
                safeCriteria.to()
        );
    }

    private TradeFilterCriteria normalizeTradeFilterCriteria(TradeFilterCriteria criteria) {
        if (criteria == null) {
            return new TradeFilterCriteria(null, null, null, null, null, null, null, null);
        }
        LocalDate from = criteria.from();
        LocalDate to = criteria.to();
        if (from != null && to != null && from.isAfter(to)) {
            LocalDate temp = from;
            from = to;
            to = temp;
        }
        return new TradeFilterCriteria(
                criteria.view(),
                criteria.setup(),
                criteria.session(),
                criteria.symbol(),
                criteria.result(),
                criteria.mistake(),
                from,
                to
        );
    }

    private Object buildDeleteSuccessResponse(
            HttpServletRequest request,
            String redirectUrl,
            List<String> deletedTradeIds,
            String successMessage,
            RedirectAttributes redirectAttributes
    ) {
        if (expectsJsonResponse(request)) {
            return ResponseEntity.ok(new DeleteTradesResponse(
                    true,
                    deletedTradeIds != null ? deletedTradeIds.size() : 0,
                    deletedTradeIds != null ? List.copyOf(deletedTradeIds) : List.of(),
                    successMessage
            ));
        }
        redirectAttributes.addFlashAttribute("tradeDeleteSuccess", successMessage);
        return redirectUrl;
    }

    private Object buildDeleteErrorResponse(
            HttpServletRequest request,
            String redirectUrl,
            String errorMessage,
            RedirectAttributes redirectAttributes,
            HttpStatus status
    ) {
        if (expectsJsonResponse(request)) {
            return ResponseEntity.status(status).body(new DeleteTradesResponse(false, 0, List.of(), errorMessage));
        }
        redirectAttributes.addFlashAttribute("tradeDeleteError", errorMessage);
        return redirectUrl;
    }

    private Object buildDeleteUnauthenticatedResponse(HttpServletRequest request) {
        if (expectsJsonResponse(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new DeleteTradesResponse(
                    false,
                    0,
                    List.of(),
                    "Your session expired. Refresh the page and sign in again."
            ));
        }
        return "redirect:/login";
    }

    private boolean expectsJsonResponse(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
            return true;
        }
        String requestedWith = request.getHeader("X-Requested-With");
        return requestedWith != null && "XMLHttpRequest".equalsIgnoreCase(requestedWith.trim());
    }

    private LocalDateTime resolveTradeTimestamp(Trade trade) {
        if (trade.getEntryTime() != null) {
            return trade.getEntryTime();
        }
        if (trade.getTradeDate() != null) {
            return trade.getTradeDate();
        }
        return trade.getCreatedAt();
    }

    private AiReviewedTradeListData buildAiReviewedTradeListData(List<Trade> trades) {
        if (trades == null || trades.isEmpty()) {
            return AiReviewedTradeListData.empty();
        }

        List<String> tradeIds = trades.stream()
                .map(Trade::getId)
                .filter(Objects::nonNull)
                .toList();
        if (tradeIds.isEmpty()) {
            return AiReviewedTradeListData.empty();
        }

        Map<String, TradeReview> reviewByTradeId = tradeReviewRepository.findByTradeIdIn(tradeIds).stream()
                .filter(review -> review.getTrade() != null && review.getTrade().getId() != null)
                .collect(Collectors.toMap(
                        review -> review.getTrade().getId(),
                        review -> review,
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));

        List<Trade> aiReviewedTrades = new ArrayList<>();
        Map<String, Integer> processScores = new LinkedHashMap<>();
        Map<String, Integer> aiProcessScores = new LinkedHashMap<>();
        Map<String, String> classifications = new LinkedHashMap<>();
        Map<String, String> classificationClasses = new LinkedHashMap<>();
        Map<String, List<String>> mistakeTags = new LinkedHashMap<>();
        Map<String, LocalDateTime> reviewTimestamps = new LinkedHashMap<>();
        Map<String, String> filterMistakeValues = new LinkedHashMap<>();

        for (Trade trade : trades) {
            TradeReview review = reviewByTradeId.get(trade.getId());
            Integer processScore = review != null ? review.getQualityScore() : null;
            processScores.put(trade.getId(), processScore);
            if (review == null || processScore == null) {
                continue;
            }

            String classification = resolveAiClassification(trade, processScore);
            List<String> visibleMistakeTags = resolveVisibleAiMistakeTags(trade);
            LocalDateTime reviewedAt = resolveAiReviewTimestamp(review);

            aiReviewedTrades.add(trade);
            aiProcessScores.put(trade.getId(), processScore);
            classifications.put(trade.getId(), classification);
            classificationClasses.put(trade.getId(), resolveAiClassificationClass(classification));
            mistakeTags.put(trade.getId(), visibleMistakeTags);
            reviewTimestamps.put(trade.getId(), reviewedAt);
            filterMistakeValues.put(trade.getId(), String.join("|", visibleMistakeTags));
        }

        Comparator<LocalDateTime> descendingTime = Comparator.nullsLast(Comparator.reverseOrder());
        aiReviewedTrades.sort(Comparator
                .comparing((Trade trade) -> reviewTimestamps.get(trade.getId()), descendingTime)
                .thenComparing(this::resolveTradeTimestamp, descendingTime));

        return new AiReviewedTradeListData(
                aiReviewedTrades,
                processScores,
                aiProcessScores,
                classifications,
                classificationClasses,
                mistakeTags,
                reviewTimestamps,
                filterMistakeValues
        );
    }

    private LocalDateTime resolveAiReviewTimestamp(TradeReview review) {
        if (review == null) {
            return null;
        }
        if (review.getUpdatedAt() != null) {
            return review.getUpdatedAt();
        }
        return review.getCreatedAt();
    }

    private List<String> resolveVisibleAiMistakeTags(Trade trade) {
        LinkedHashSet<String> uniqueTags = new LinkedHashSet<>();
        if (trade == null || trade.getMistakes() == null) {
            return List.of();
        }
        trade.getMistakes().stream()
                .map(mistake -> mistake != null ? trimToNull(mistake.getName()) : null)
                .filter(Objects::nonNull)
                .forEach(uniqueTags::add);
        return uniqueTags.isEmpty() ? List.of() : List.copyOf(uniqueTags);
    }

    private String resolveAiClassification(Trade trade, Integer processScore) {
        if (trade == null || processScore == null) {
            return "AI review available";
        }

        boolean goodProcess = processScore >= 70;
        boolean goodOutcome = trade.getPnl() > 0;

        if (goodProcess && goodOutcome) {
            return "Good process / Good outcome";
        }
        if (goodProcess) {
            return "Good process / Bad outcome";
        }
        if (goodOutcome) {
            return "Bad process / Good outcome";
        }
        return "Bad process / Bad outcome";
    }

    private String resolveAiClassificationClass(String classification) {
        if (classification == null || classification.isBlank() || "AI review available".equalsIgnoreCase(classification)) {
            return " trade-ai-classification-neutral";
        }
        if ("Good process / Good outcome".equalsIgnoreCase(classification)) {
            return " trade-ai-classification-good-good";
        }
        if ("Good process / Bad outcome".equalsIgnoreCase(classification)) {
            return " trade-ai-classification-good-bad";
        }
        if ("Bad process / Good outcome".equalsIgnoreCase(classification)) {
            return " trade-ai-classification-bad-good";
        }
        return " trade-ai-classification-bad-bad";
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record AiReviewedTradeListData(
            List<Trade> trades,
            Map<String, Integer> processScores,
            Map<String, Integer> aiProcessScores,
            Map<String, String> classifications,
            Map<String, String> classificationClasses,
            Map<String, List<String>> mistakeTags,
            Map<String, LocalDateTime> reviewTimestamps,
            Map<String, String> filterMistakeValues
    ) {
        private static AiReviewedTradeListData empty() {
            return new AiReviewedTradeListData(
                    List.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of()
            );
        }
    }

    private void populateTradeDetailModel(Model model, User currentUser, Trade trade, TradeReview overrideReview) {
        TradeReview review = overrideReview != null ? overrideReview : tradeReviewService.getOrInitByTrade(trade);
        AiTradeReviewService.AiReviewView aiReviewView = tradeAiReviewService.resolveView(review);
        Integer processScoreValue = tradeReviewService.resolveEffectiveQualityScore(review);
        String processScoreSourceLabel = tradeReviewService.resolveEffectiveQualitySourceLabel(review);

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("trade", trade);
        model.addAttribute("tradeImages", tradeImageService.findByTradeId(trade.getId()));
        model.addAttribute("review", review);
        model.addAttribute("qualityGrade", tradeReviewService.resolveScoreGrade(review.getQualityScore()));
        model.addAttribute("qualityLabel", tradeReviewService.resolveScoreLabel(review.getQualityScore()));
        model.addAttribute("processScoreValue", processScoreValue);
        model.addAttribute("processScoreGrade", tradeReviewService.resolveScoreGrade(processScoreValue));
        model.addAttribute("processScoreLabel", tradeReviewService.resolveScoreLabel(processScoreValue));
        model.addAttribute("processScoreSourceLabel", processScoreSourceLabel);
        model.addAttribute("processOutcomeLabel", tradeReviewService.resolveProcessOutcomeLabel(trade, review));
        model.addAttribute("aiReviewConfigured", tradeAiReviewService.isConfigured());
        if (!model.containsAttribute("aiReviewState")) {
            model.addAttribute("aiReviewState", aiReviewView.state());
        }
        if (!model.containsAttribute("aiReviewSummary")) {
            model.addAttribute("aiReviewSummary", aiReviewView.summary());
        }
        if (!model.containsAttribute("aiReviewStrengths")) {
            model.addAttribute("aiReviewStrengths", aiReviewView.strengths());
        }
        if (!model.containsAttribute("aiReviewWeaknesses")) {
            model.addAttribute("aiReviewWeaknesses", aiReviewView.weaknesses());
        }
        if (!model.containsAttribute("aiReviewImprovements")) {
            model.addAttribute("aiReviewImprovements", aiReviewView.improvements());
        }
        if (!model.containsAttribute("aiReviewSuggestedMistakeTags")) {
            model.addAttribute("aiReviewSuggestedMistakeTags", aiReviewView.suggestedMistakeTags());
        }
        if (!model.containsAttribute("aiReviewConfidence")) {
            model.addAttribute("aiReviewConfidence", aiReviewView.confidence());
        }
        if (!model.containsAttribute("aiReviewModel")) {
            model.addAttribute("aiReviewModel", aiReviewView.model());
        }
        if (!model.containsAttribute("aiReviewGeneratedAt")) {
            model.addAttribute("aiReviewGeneratedAt", aiReviewView.generatedAt());
        }
        if (!model.containsAttribute("aiReviewProcessScore")) {
            model.addAttribute("aiReviewProcessScore", aiReviewView.processScore());
        }
    }

    private String buildFormErrorSummary(BindingResult bindingResult) {
        if (bindingResult == null || !bindingResult.hasErrors()) {
            return "Please review the highlighted fields and try again.";
        }

        return bindingResult.getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .distinct()
                .reduce((left, right) -> left + " | " + right)
                .orElse("Please review the highlighted fields and try again.");
    }

    private void applySubmittedSetup(Trade trade, String setupId) {
        if (trade == null) {
            return;
        }

        if (setupId == null || setupId.isBlank()) {
            trade.setSetup(null);
            return;
        }

        Setup setup = trade.getSetup();
        if (setup == null) {
            setup = new Setup();
            trade.setSetup(setup);
        }
        setup.setId(setupId.trim());
    }

    private record DeleteTradesResponse(
            boolean success,
            int deletedCount,
            List<String> deletedTradeIds,
            String message
    ) {
    }
}
