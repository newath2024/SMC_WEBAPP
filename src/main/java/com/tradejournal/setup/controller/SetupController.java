package com.tradejournal.setup.controller;

import com.tradejournal.setup.domain.Setup;
import com.tradejournal.auth.domain.User;
import com.tradejournal.trade.repository.TradeRepository;
import com.tradejournal.setup.service.SetupService;
import com.tradejournal.auth.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/setups")
public class SetupController {

    private final SetupService setupService;
    private final UserService userService;
    private final TradeRepository tradeRepository;

    public SetupController(SetupService setupService, UserService userService, TradeRepository tradeRepository) {
        this.setupService = setupService;
        this.userService = userService;
        this.tradeRepository = tradeRepository;
    }

    @GetMapping
    public String list(Model model, HttpSession session) {

        User user = userService.getCurrentUser(session);
        if (user == null) return "redirect:/login";

        model.addAttribute("currentUser", user);

        boolean adminView = userService.isAdmin(user);
        model.addAttribute("adminView", adminView);
        if (adminView) {
            model.addAttribute("setups", setupService.findAllForAdmin());
        } else {
            model.addAttribute("setups", setupService.findByUserIncludingArchived(user.getId()));
        }
        model.addAttribute("setupStats", loadSetupStats(adminView, user));

        return "setups";
    }

    @GetMapping("/new")
    public String createForm(Model model, HttpSession session) {

        User user = userService.getCurrentUser(session);
        if (user == null) return "redirect:/login";

        model.addAttribute("setup", new Setup());
        model.addAttribute("currentUser", user);
        model.addAttribute("editMode", false);
        if (!userService.hasProAccess(user) && setupService.findByUserIncludingArchived(user.getId()).size() >= userService.resolveSetupLimit(user)) {
            model.addAttribute("error", "Standard plan includes up to 5 setups. Upgrade to Pro for unlimited setups.");
        }

        return "setupForm";
    }

    @PostMapping
    public String create(@ModelAttribute Setup setup, HttpSession session, Model model) {

        User user = userService.getCurrentUser(session);
        if (user == null) return "redirect:/login";

        if (!userService.hasProAccess(user) && setupService.findByUserIncludingArchived(user.getId()).size() >= userService.resolveSetupLimit(user)) {
            model.addAttribute("setup", setup);
            model.addAttribute("currentUser", user);
            model.addAttribute("editMode", false);
            model.addAttribute("error", "Standard plan includes up to 5 setups. Upgrade to Pro for unlimited setups.");
            return "setupForm";
        }

        try {
            setupService.create(
                    setup.getName(),
                    setup.getDescription(),
                    user
            );
        } catch (IllegalArgumentException ex) {
            model.addAttribute("setup", setup);
            model.addAttribute("currentUser", user);
            model.addAttribute("editMode", false);
            model.addAttribute("error", ex.getMessage());
            return "setupForm";
        }

        return "redirect:/setups";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable String id, Model model, HttpSession session) {
        User user = userService.getCurrentUser(session);
        if (user == null) return "redirect:/login";

        Setup setup = userService.isAdmin(user)
                ? setupService.findByIdForAdmin(id)
                : setupService.findByIdForUser(id, user.getId());

        model.addAttribute("setup", setup);
        model.addAttribute("currentUser", user);
        model.addAttribute("editMode", true);

        return "setupForm";
    }

    @PostMapping("/{id}/edit")
    public String update(
            @PathVariable String id,
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestParam(defaultValue = "true") boolean active,
            HttpSession session,
            Model model
    ) {
        User user = userService.getCurrentUser(session);
        if (user == null) return "redirect:/login";

        try {
            if (userService.isAdmin(user)) {
                setupService.updateForAdmin(id, name, description, active);
            } else {
                setupService.updateForUser(id, user.getId(), name, description, active);
            }
            return "redirect:/setups";
        } catch (IllegalArgumentException ex) {
            Setup setup = new Setup();
            setup.setId(id);
            setup.setName(name);
            setup.setDescription(description);
            setup.setActive(active);

            model.addAttribute("setup", setup);
            model.addAttribute("currentUser", user);
            model.addAttribute("editMode", true);
            model.addAttribute("error", ex.getMessage());
            return "setupForm";
        }
    }

    @PostMapping("/{id}/toggle-active")
    public String toggleActive(
            @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean active,
            HttpSession session
    ) {
        User user = userService.getCurrentUser(session);
        if (user == null) return "redirect:/login";

        if (userService.isAdmin(user)) {
            setupService.setActiveForAdmin(id, active);
        } else {
            setupService.setActiveForUser(id, user.getId(), active);
        }

        return "redirect:/setups";
    }

    private Map<String, SetupStatsView> loadSetupStats(boolean adminView, User user) {
        List<TradeRepository.SetupTradeMetricsRow> rows = adminView
                ? tradeRepository.summarizeBySetupAllUsers()
                : tradeRepository.summarizeBySetupForUser(user.getId());

        Map<String, SetupStatsView> statsBySetupId = new HashMap<>();
        for (TradeRepository.SetupTradeMetricsRow row : rows) {
            long tradeCount = row.getTradeCount();
            double winRate = tradeCount == 0 ? 0.0 : (row.getWinCount() * 100.0) / tradeCount;
            double averageR = row.getAverageR() == null ? 0.0 : row.getAverageR();
            double totalPnl = row.getTotalPnl() == null ? 0.0 : row.getTotalPnl();
            statsBySetupId.put(row.getSetupId(), new SetupStatsView(tradeCount, winRate, averageR, totalPnl));
        }
        return statsBySetupId;
    }

    public static class SetupStatsView {
        private final long tradeCount;
        private final double winRate;
        private final double averageR;
        private final double totalPnl;

        public SetupStatsView(long tradeCount, double winRate, double averageR, double totalPnl) {
            this.tradeCount = tradeCount;
            this.winRate = winRate;
            this.averageR = averageR;
            this.totalPnl = totalPnl;
        }

        public long getTradeCount() {
            return tradeCount;
        }

        public double getWinRate() {
            return winRate;
        }

        public double getAverageR() {
            return averageR;
        }

        public double getTotalPnl() {
            return totalPnl;
        }
    }
}
