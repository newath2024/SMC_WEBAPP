package com.example.demo.controller;

import com.example.demo.entity.Trade;
import com.example.demo.entity.TradeReview;
import com.example.demo.entity.User;
import com.example.demo.service.MistakeTagService;
import com.example.demo.service.TradeImageService;
import com.example.demo.service.TradeReviewService;
import com.example.demo.service.SetupService;
import com.example.demo.service.TradeService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

@Controller
@RequestMapping("/trades")
public class TradeController {

    private final TradeService tradeService;
    private final UserService userService;
    private final SetupService setupService;
    private final MistakeTagService mistakeTagService;
    private final TradeImageService tradeImageService;
    private final TradeReviewService tradeReviewService;

    public TradeController(
            TradeService tradeService,
            UserService userService,
            SetupService setupService,
            MistakeTagService mistakeTagService,
            TradeImageService tradeImageService,
            TradeReviewService tradeReviewService
    ) {
        this.tradeService = tradeService;
        this.userService = userService;
        this.setupService = setupService;
        this.mistakeTagService = mistakeTagService;
        this.tradeImageService = tradeImageService;
        this.tradeReviewService = tradeReviewService;
    }

    private void fillTradeFormData(Model model, User currentUser) {
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("setups", setupService.findActiveByUser(currentUser.getId()));
        model.addAttribute("mistakeTags", mistakeTagService.findActive());
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
        double averageR = trades.isEmpty() ? 0.0 : trades.stream().mapToDouble(Trade::getRMultiple).average().orElse(0.0);

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
        fillTradeFormData(model, currentUser);
        return "tradeForm";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("trade") Trade trade,
            BindingResult bindingResult,
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

        if (trade.getSetup() == null || trade.getSetup().getId() == null || trade.getSetup().getId().isBlank()) {
            bindingResult.rejectValue("setup", "required", "Setup is required");
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("editMode", false);
            fillTradeFormData(model, currentUser);
            model.addAttribute("trade", trade);
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
            @RequestParam(value = "mistakeIds", required = false) List<String> mistakeIds,
            @RequestParam(value = "customMistakes", required = false) String customMistakes,
            Model model,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        if (trade.getSetup() == null || trade.getSetup().getId() == null || trade.getSetup().getId().isBlank()) {
            bindingResult.rejectValue("setup", "required", "Setup is required");
        }

        if (bindingResult.hasErrors()) {
            trade.setId(id);
            model.addAttribute("trade", trade);
            model.addAttribute("editMode", true);
            model.addAttribute("tradeImages", tradeImageService.findByTradeId(id));
            fillTradeFormData(model, currentUser);
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
}
