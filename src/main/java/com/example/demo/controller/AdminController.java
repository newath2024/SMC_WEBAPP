package com.example.demo.controller;

import com.example.demo.entity.Trade;
import com.example.demo.entity.User;
import com.example.demo.repository.TradeRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.TradeImageService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final TradeRepository tradeRepository;
    private final UserService userService;
    private final TradeImageService tradeImageService;

    public AdminController(
            UserRepository userRepository,
            TradeRepository tradeRepository,
            UserService userService,
            TradeImageService tradeImageService
    ) {
        this.userRepository = userRepository;
        this.tradeRepository = tradeRepository;
        this.userService = userService;
        this.tradeImageService = tradeImageService;
    }

    @GetMapping
    public String dashboard(Model model, HttpSession session) {
        User admin = userService.getCurrentUser(session);

        if (admin == null) {
            return "redirect:/login";
        }

        if (!userService.isAdmin(admin)) {
            return "redirect:/trades";
        }

        List<User> users = userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Trade> trades = tradeRepository.findAll(Sort.by(Sort.Direction.DESC, "entryTime"));

        model.addAttribute("currentUser", admin);
        model.addAttribute("users", users);
        model.addAttribute("trades", trades);
        model.addAttribute("totalUsers", users.size());
        model.addAttribute("activeUsers", users.stream().filter(User::isActive).count());
        model.addAttribute("inactiveUsers", users.stream().filter(u -> !u.isActive()).count());
        model.addAttribute("totalTrades", trades.size());

        return "admin";
    }

    @PostMapping("/users/{id}/toggle-active")
    public String toggleUserActive(@PathVariable String id, HttpSession session) {
        User admin = userService.getCurrentUser(session);

        if (admin == null) {
            return "redirect:/login";
        }

        if (!userService.isAdmin(admin)) {
            return "redirect:/trades";
        }

        User target = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));

        if (admin.getId().equals(target.getId())) {
            throw new IllegalArgumentException("You cannot disable yourself");
        }

        target.setActive(!target.isActive());
        userRepository.save(target);

        return "redirect:/admin";
    }

    @PostMapping("/users/{id}/delete")
    public String deleteUser(@PathVariable String id, HttpSession session) {
        User admin = userService.getCurrentUser(session);

        if (admin == null) {
            return "redirect:/login";
        }

        if (!userService.isAdmin(admin)) {
            return "redirect:/trades";
        }

        User target = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));

        if (admin.getId().equals(target.getId())) {
            throw new IllegalArgumentException("You cannot delete yourself");
        }

        List<Trade> userTrades = tradeRepository.findByUserIdOrderByEntryTimeDesc(target.getId());
        for (Trade trade : userTrades) {
            tradeImageService.deleteByTradeId(trade.getId());
        }
        tradeRepository.deleteAll(userTrades);

        userRepository.delete(target);

        return "redirect:/admin";
    }

    @PostMapping("/trades/{id}/delete")
    public String deleteTrade(@PathVariable String id, HttpSession session) {
        User admin = userService.getCurrentUser(session);

        if (admin == null) {
            return "redirect:/login";
        }

        if (!userService.isAdmin(admin)) {
            return "redirect:/trades";
        }

        Trade trade = tradeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Trade not found: " + id));

        tradeImageService.deleteByTradeId(trade.getId());
        tradeRepository.delete(trade);

        return "redirect:/admin";
    }
}
