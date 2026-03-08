package com.example.demo.controller;

import com.example.demo.entity.Trade;
import com.example.demo.entity.User;
import com.example.demo.service.TradeService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/trades")
public class TradeController {

    private final TradeService tradeService;
    private final UserService userService;

    public TradeController(TradeService tradeService, UserService userService) {
        this.tradeService = tradeService;
        this.userService = userService;
    }

    @GetMapping
    public String list(Model model, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("trades", tradeService.findAllByUser(currentUser.getId()));
        model.addAttribute("trade", new Trade());

        return "trades";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("trade") Trade trade,
            BindingResult bindingResult,
            Model model,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("currentUser", currentUser);
            model.addAttribute("trades", tradeService.findAllByUser(currentUser.getId()));
            return "trades";
        }

        tradeService.saveForUser(trade, currentUser);
        return "redirect:/trades";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        Trade trade;
        if (userService.isAdmin(currentUser)) {
            trade = tradeService.findByIdForAdmin(id);
        } else {
            trade = tradeService.findByIdForUser(id, currentUser.getId());
        }

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("trade", trade);
        return "tradeDetail";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        tradeService.deleteForUser(id, currentUser.getId());
        return "redirect:/trades";
    }
}