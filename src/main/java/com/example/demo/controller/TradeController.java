package com.example.demo.controller;

import com.example.demo.entity.Trade;
import com.example.demo.entity.User;
import com.example.demo.service.TradeService;
import com.example.demo.service.UserService;

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

    /**
     * Trang danh sách trade
     */
    @GetMapping
    public String list(Model model) {

        User currentUser = userService.getCurrentUser();

        model.addAttribute(
                "trades",
                tradeService.findAllByUser(currentUser.getId())
        );

        model.addAttribute("trade", new Trade());

        return "trades";
    }

    /**
     * Lưu trade
     */
    @PostMapping
    public String create(
            @Valid @ModelAttribute("trade") Trade trade,
            BindingResult bindingResult,
            Model model
    ) {

        User currentUser = userService.getCurrentUser();

        if (bindingResult.hasErrors()) {

            model.addAttribute(
                    "trades",
                    tradeService.findAllByUser(currentUser.getId())
            );

            return "trades";
        }

        tradeService.saveForUser(trade, currentUser);

        return "redirect:/trades";
    }

    /**
     * Trang chi tiết trade
     */
    @GetMapping("/{id}")
    public String detail(
            @PathVariable String id,
            Model model
    ) {

        User currentUser = userService.getCurrentUser();

        Trade trade = tradeService.findByIdForUser(
                id,
                currentUser.getId()
        );

        model.addAttribute("trade", trade);

        return "tradeDetail";
    }

    /**
     * Xóa trade
     */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id) {

        User currentUser = userService.getCurrentUser();

        tradeService.deleteForUser(id, currentUser.getId());

        return "redirect:/trades";
    }
}