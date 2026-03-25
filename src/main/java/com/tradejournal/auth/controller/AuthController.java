package com.tradejournal.controller;

import com.tradejournal.entity.User;
import com.tradejournal.service.AuthService;
import com.tradejournal.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class AuthController {

    public static final String SESSION_USER_ID = "CURRENT_USER_ID";

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @GetMapping("/login")
    public String loginPage(
            @RequestParam(value = "accountDeleted", required = false) String accountDeleted,
            HttpSession session,
            Model model
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser != null) {
            return redirectAfterAuth(currentUser);
        }
        if ("1".equals(accountDeleted)) {
            model.addAttribute("message", "Your account and related journal data were deleted successfully.");
        }
        return "login";
    }

    @PostMapping("/login")
    public String login(
            @RequestParam("usernameOrEmail") String usernameOrEmail,
            @RequestParam("password") String password,
            HttpSession session,
            Model model
    ) {
        try {
            User user = authService.login(usernameOrEmail, password);
            session.setAttribute(SESSION_USER_ID, user.getId());
            return redirectAfterAuth(user);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("usernameOrEmail", usernameOrEmail);
            return "login";
        }
    }

    @GetMapping("/register")
    public String registerPage(HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser != null) {
            return redirectAfterAuth(currentUser);
        }
        return "register";
    }

    @PostMapping("/register")
    public String register(
            @RequestParam("username") String username,
            @RequestParam("email") String email,
            @RequestParam("password") String password,
            @RequestParam("confirmPassword") String confirmPassword,
            HttpSession session,
            Model model
    ) {
        if (confirmPassword == null || !confirmPassword.equals(password)) {
            model.addAttribute("error", "Confirm password does not match");
            model.addAttribute("username", username);
            model.addAttribute("email", email);
            return "register";
        }

        try {
            User user = authService.register(username, email, password);
            session.setAttribute(SESSION_USER_ID, user.getId());
            return redirectAfterAuth(user);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("username", username);
            model.addAttribute("email", email);
            return "register";
        }
    }

    @PostMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    @GetMapping("/")
    public String home(HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser != null) {
            return redirectAfterAuth(currentUser);
        }
        return "index";
    }

    private String redirectAfterAuth(User user) {
        if (userService.isAdmin(user)) {
            return "redirect:/admin";
        }
        return "redirect:/dashboard";
    }
}
