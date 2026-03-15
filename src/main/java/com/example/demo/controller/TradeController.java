package com.example.demo.controller;

import com.example.demo.entity.Trade;
import com.example.demo.entity.TradeReview;
import com.example.demo.entity.User;
import com.example.demo.entity.Setup;
import com.example.demo.service.MistakeTagService;
import com.example.demo.service.Mt5ImportService;
import com.example.demo.service.TradeImageService;
import com.example.demo.service.TradeReviewService;
import com.example.demo.service.SetupService;
import com.example.demo.service.TradeService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

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
    private final Mt5ImportService mt5ImportService;

    public TradeController(
            TradeService tradeService,
            UserService userService,
            SetupService setupService,
            MistakeTagService mistakeTagService,
            TradeImageService tradeImageService,
            TradeReviewService tradeReviewService,
            Mt5ImportService mt5ImportService
    ) {
        this.tradeService = tradeService;
        this.userService = userService;
        this.setupService = setupService;
        this.mistakeTagService = mistakeTagService;
        this.tradeImageService = tradeImageService;
        this.tradeReviewService = tradeReviewService;
        this.mt5ImportService = mt5ImportService;
    }

    private Mt5ImportService.ImportPreview getStoredImportPreview(HttpSession session, User currentUser) {
        Object attribute = session.getAttribute(MT5_IMPORT_PREVIEW_SESSION_KEY);
        if (!(attribute instanceof Mt5ImportService.ImportPreview preview)) {
            return null;
        }
        if (currentUser == null || !Objects.equals(preview.userId(), currentUser.getId())) {
            session.removeAttribute(MT5_IMPORT_PREVIEW_SESSION_KEY);
            return null;
        }
        return preview;
    }

    private void storeImportPreview(HttpSession session, Mt5ImportService.ImportPreview preview) {
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
    }

    private void fillTradeListData(
            Model model,
            User currentUser,
            List<Trade> trades,
            boolean filteredView,
            boolean tableOnlyView,
            boolean adminView,
            String setup,
            String sessionFilter,
            String symbol,
            LocalDate from,
            LocalDate to
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
        fillTradeFormData(model, currentUser);
    }

    @GetMapping
    public String list(
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "setup", required = false) String setup,
            @RequestParam(value = "session", required = false) String sessionFilter,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Model model,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        LocalDateTime fromDateTime = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDateTime = to != null ? to.atTime(LocalTime.MAX) : null;
        if (fromDateTime != null && toDateTime != null && fromDateTime.isAfter(toDateTime)) {
            LocalDateTime temp = fromDateTime;
            fromDateTime = toDateTime;
            toDateTime = temp;
        }

        List<Trade> trades = tradeService.findAllByUser(currentUser.getId());
        trades = applyListFilters(trades, setup, sessionFilter, symbol, fromDateTime, toDateTime);
        boolean tableOnlyView = "table".equalsIgnoreCase(view);
        boolean filteredView = tableOnlyView || hasActiveFilter(setup, sessionFilter, symbol, from, to);
        boolean adminView = userService.isAdmin(currentUser);

        fillTradeListData(model, currentUser, trades, filteredView, tableOnlyView, adminView, setup, sessionFilter, symbol, from, to);
        model.addAttribute("mt5ImportPreview", getStoredImportPreview(session, currentUser));

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
        fillTradeFormData(model, currentUser);
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
            Mt5ImportService.ImportPreview preview = mt5ImportService.previewWorkbook(
                    file,
                    currentUser,
                    new Mt5ImportService.ImportOptions(setupName, defaultHtf, defaultLtf, sessionMode)
            );
            storeImportPreview(session, preview);
            redirectAttributes.addFlashAttribute("importInfo", "Preview ready. Review the parsed trades below, then confirm to import.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            clearImportPreview(session);
            redirectAttributes.addFlashAttribute("importError", ex.getMessage());
        }

        return "redirect:/trades";
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

        Mt5ImportService.ImportPreview preview = getStoredImportPreview(session, currentUser);
        if (preview == null) {
            redirectAttributes.addFlashAttribute("importError", "No MT5 import preview is available. Please upload the file again.");
            return "redirect:/trades";
        }
        if (!Objects.equals(preview.previewId(), previewId)) {
            redirectAttributes.addFlashAttribute("importError", "This preview is outdated. Please review the latest MT5 preview before confirming.");
            return "redirect:/trades";
        }

        try {
            Mt5ImportService.ImportResult result = mt5ImportService.confirmImport(currentUser, preview);
            clearImportPreview(session);

            String successMessage = "Imported " + result.importedCount()
                    + " trades into setup '" + result.setupName()
                    + "' from account '" + result.accountLabel()
                    + "'. Duplicates skipped: " + result.duplicateCount()
                    + ". Unusable rows skipped: " + result.skippedCount() + ".";
            redirectAttributes.addFlashAttribute("importSuccess", successMessage);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("importError", ex.getMessage());
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

        Mt5ImportService.ImportPreview preview = getStoredImportPreview(session, currentUser);
        if (preview != null && Objects.equals(preview.previewId(), previewId)) {
            clearImportPreview(session);
            redirectAttributes.addFlashAttribute("importInfo", "MT5 import preview cleared.");
        }

        return "redirect:/trades";
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
            fillTradeFormData(model, currentUser);
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
            fillTradeFormData(model, currentUser);
            model.addAttribute("trade", trade);
            model.addAttribute("error", buildFormErrorSummary(bindingResult));
            return "tradeForm";
        }

        try {
            tradeService.saveForUser(trade, currentUser, mistakeIds, customMistakes);
            return "redirect:/trades";
        } catch (RuntimeException ex) {
            model.addAttribute("editMode", false);
            fillTradeFormData(model, currentUser);
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
    public String delete(@PathVariable String id, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        if (userService.isAdmin(currentUser)) {
            tradeImageService.deleteByTradeId(id);
            tradeService.deleteForAdmin(id);
        } else {
            tradeImageService.deleteByTradeId(id);
            tradeService.deleteForUser(id, currentUser.getId());
        }

        return "redirect:/trades";
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

    private List<Trade> applyListFilters(
            List<Trade> trades,
            String setup,
            String sessionFilter,
            String symbol,
            LocalDateTime from,
            LocalDateTime to
    ) {
        return trades.stream()
                .filter(trade -> matchesTextFilter(trade.getSetupName(), setup))
                .filter(trade -> matchesTextFilter(trade.getSession(), sessionFilter))
                .filter(trade -> matchesTextFilter(trade.getSymbol(), symbol))
                .filter(trade -> matchesDateFilter(resolveTradeTimestamp(trade), from, to))
                .toList();
    }

    private boolean matchesTextFilter(String value, String filter) {
        if (filter == null || filter.isBlank() || "N/A".equalsIgnoreCase(filter)) {
            return true;
        }
        if (value == null) {
            return false;
        }
        return value.trim().equalsIgnoreCase(filter.trim());
    }

    private boolean matchesDateFilter(LocalDateTime timestamp, LocalDateTime from, LocalDateTime to) {
        if (from == null && to == null) {
            return true;
        }
        if (timestamp == null) {
            return false;
        }
        if (from != null && timestamp.isBefore(from)) {
            return false;
        }
        if (to != null && timestamp.isAfter(to)) {
            return false;
        }
        return true;
    }

    private boolean hasActiveFilter(String setup, String sessionFilter, String symbol, LocalDate from, LocalDate to) {
        return (setup != null && !setup.isBlank())
                || (sessionFilter != null && !sessionFilter.isBlank())
                || (symbol != null && !symbol.isBlank())
                || from != null
                || to != null;
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

    private void populateTradeDetailModel(Model model, User currentUser, Trade trade, TradeReview overrideReview) {
        TradeReview review = overrideReview != null ? overrideReview : tradeReviewService.getOrInitByTrade(trade);

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("trade", trade);
        model.addAttribute("tradeImages", tradeImageService.findByTradeId(trade.getId()));
        model.addAttribute("review", review);
        model.addAttribute("qualityGrade", tradeReviewService.resolveScoreGrade(review.getQualityScore()));
        model.addAttribute("qualityLabel", tradeReviewService.resolveScoreLabel(review.getQualityScore()));
        model.addAttribute("processOutcomeLabel", tradeReviewService.resolveProcessOutcomeLabel(trade, review));
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
}
