package com.example.demo.controller;

import com.example.demo.entity.MistakeTag;
import com.example.demo.entity.User;
import com.example.demo.service.MistakeTagService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/mistakes")
public class MistakeTagController {

    private final MistakeTagService mistakeTagService;
    private final UserService userService;

    public MistakeTagController(MistakeTagService mistakeTagService, UserService userService) {
        this.mistakeTagService = mistakeTagService;
        this.userService = userService;
    }

    @GetMapping
    public String list(Model model, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("mistakes", mistakeTagService.findAll());

        return "mistakes";
    }

    @GetMapping("/new")
    public String createForm(Model model, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("mistake", new MistakeTag());
        model.addAttribute("editMode", false);

        return "mistakeForm";
    }

    @PostMapping
    public String create(
            @RequestParam String code,
            @RequestParam String name,
            @RequestParam(required = false) String description,
            HttpSession session,
            Model model
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        try {
            mistakeTagService.create(code, name, description);
            return "redirect:/mistakes";
        } catch (IllegalArgumentException e) {
            MistakeTag mistake = new MistakeTag();
            mistake.setCode(code);
            mistake.setName(name);
            mistake.setDescription(description);

            model.addAttribute("currentUser", currentUser);
            model.addAttribute("mistake", mistake);
            model.addAttribute("editMode", false);
            model.addAttribute("error", e.getMessage());
            return "mistakeForm";
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable String id, Model model, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("mistake", mistakeTagService.findById(id));
        model.addAttribute("editMode", true);

        return "mistakeForm";
    }

    @PostMapping("/{id}/edit")
    public String update(
            @PathVariable String id,
            @RequestParam String code,
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestParam(defaultValue = "false") boolean active,
            HttpSession session,
            Model model
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        try {
            mistakeTagService.update(id, code, name, description, active);
            return "redirect:/mistakes";
        } catch (IllegalArgumentException e) {
            MistakeTag mistake = new MistakeTag();
            mistake.setId(id);
            mistake.setCode(code);
            mistake.setName(name);
            mistake.setDescription(description);
            mistake.setActive(active);

            model.addAttribute("currentUser", currentUser);
            model.addAttribute("mistake", mistake);
            model.addAttribute("editMode", true);
            model.addAttribute("error", e.getMessage());
            return "mistakeForm";
        }
    }

    @PostMapping("/{id}/toggle-active")
    public String toggleActive(@PathVariable String id, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        mistakeTagService.toggleActive(id);
        return "redirect:/mistakes";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        mistakeTagService.delete(id);
        return "redirect:/mistakes";
    }
}
