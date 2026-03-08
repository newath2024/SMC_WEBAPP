package com.example.demo.controller;

import com.example.demo.entity.Trade;
import com.example.demo.entity.User;
import com.example.demo.service.MistakeTagService;
import com.example.demo.service.SetupService;
import com.example.demo.service.TradeService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/trades")
public class TradeController {

    private final TradeService tradeService;
    private final UserService userService;
    private final SetupService setupService;
    private final MistakeTagService mistakeTagService;

    public TradeController(
            TradeService tradeService,
            UserService userService,
            SetupService setupService,
            MistakeTagService mistakeTagService
    ) {
        this.tradeService = tradeService;
        this.userService = userService;
        this.setupService = setupService;
        this.mistakeTagService = mistakeTagService;
    }

    private void fillTradeFormData(Model model, User currentUser) {
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("setups", setupService.findActiveByUser(currentUser.getId()));
        model.addAttribute("mistakeTags", mistakeTagService.findActive());
    }

    @GetMapping
    public String list(Model model, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        model.addAttribute("trades", tradeService.findAllByUser(currentUser.getId()));
        model.addAttribute("trade", new Trade());
        fillTradeFormData(model, currentUser);

        return "trades";
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

        if (trade.getSetup() == null || trade.getSetup().getId() == null || trade.getSetup().getId().isBlank()) {
            bindingResult.rejectValue("setup", "required", "Setup is required");
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("trades", tradeService.findAllByUser(currentUser.getId()));
            fillTradeFormData(model, currentUser);
            model.addAttribute("trade", trade);
            return "trades";
        }

        try {
            tradeService.saveForUser(trade, currentUser, mistakeIds, customMistakes);
            return "redirect:/trades";
        } catch (RuntimeException ex) {
            model.addAttribute("trades", tradeService.findAllByUser(currentUser.getId()));
            fillTradeFormData(model, currentUser);
            model.addAttribute("trade", trade);
            model.addAttribute("error", friendlyErrorMessage(ex));
            return "trades";
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

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("trade", trade);

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
            model.addAttribute("trade", trade);
            model.addAttribute("editMode", true);
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
            model.addAttribute("trade", trade);
            model.addAttribute("editMode", true);
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
            tradeService.deleteForAdmin(id);
        } else {
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
}
