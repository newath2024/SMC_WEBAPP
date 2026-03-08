package com.example.demo.controller;

import com.example.demo.entity.Setup;
import com.example.demo.entity.User;
import com.example.demo.service.SetupService;
import com.example.demo.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;

@Controller
@RequestMapping("/setups")
public class SetupController {

    private final SetupService setupService;
    private final UserService userService;

    public SetupController(SetupService setupService, UserService userService) {
        this.setupService = setupService;
        this.userService = userService;
    }

    @GetMapping
    public String list(Model model, HttpSession session) {

        User user = userService.getCurrentUser(session);
        if (user == null) return "redirect:/login";

        model.addAttribute("currentUser", user);
        model.addAttribute("setups",
                setupService.findActiveByUser(user.getId()));

        return "setups";
    }

    @GetMapping("/new")
    public String createForm(Model model, HttpSession session) {

        User user = userService.getCurrentUser(session);
        if (user == null) return "redirect:/login";

        model.addAttribute("setup", new Setup());
        model.addAttribute("currentUser", user);

        return "setupForm";
    }

    @PostMapping
    public String create(@ModelAttribute Setup setup, HttpSession session) {

        User user = userService.getCurrentUser(session);
        if (user == null) return "redirect:/login";

        setupService.create(
                setup.getName(),
                setup.getDescription(),
                user
        );

        return "redirect:/setups";
    }
}