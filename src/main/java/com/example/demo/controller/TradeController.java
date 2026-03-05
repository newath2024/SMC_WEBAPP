package com.example.demo.controller;

import com.example.demo.entity.Trade;
import com.example.demo.service.TradeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import org.springframework.validation.BindingResult;

@Controller
@RequestMapping("/trades")
public class TradeController {

    private final TradeService service;

    public TradeController(TradeService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("trades", service.findAll());
        model.addAttribute("trade", new Trade());
        return "trades";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute Trade trade, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("trades", service.findAll());
            return "trades";
        }
        service.save(trade);
        return "redirect:/trades";
    }
}