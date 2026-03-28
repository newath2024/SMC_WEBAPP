package com.tradejournal.mistake.controller;

import com.tradejournal.mistake.domain.MistakeTag;
import com.tradejournal.trade.repository.TradeMistakeTagRepository;
import com.tradejournal.auth.domain.User;
import com.tradejournal.mistake.service.MistakeAnalyticsService;
import com.tradejournal.mistake.service.MistakeTagService;
import com.tradejournal.auth.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Controller
@RequestMapping("/mistakes")
public class MistakeTagController {

    private final MistakeTagService mistakeTagService;
    private final MistakeAnalyticsService mistakeAnalyticsService;
    private final TradeMistakeTagRepository tradeMistakeTagRepository;
    private final UserService userService;

    public MistakeTagController(
            MistakeTagService mistakeTagService,
            MistakeAnalyticsService mistakeAnalyticsService,
            TradeMistakeTagRepository tradeMistakeTagRepository,
            UserService userService
    ) {
        this.mistakeTagService = mistakeTagService;
        this.mistakeAnalyticsService = mistakeAnalyticsService;
        this.tradeMistakeTagRepository = tradeMistakeTagRepository;
        this.userService = userService;
    }

    @GetMapping
    public String list(Model model, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        boolean adminView = userService.isAdmin(currentUser);

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("adminView", adminView);

        var usageByTagId = tradeMistakeTagRepository.countUsageByMistakeTagForUser(currentUser.getId())
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        TradeMistakeTagRepository.MistakeUsageRow::getMistakeTagId,
                        TradeMistakeTagRepository.MistakeUsageRow::getUsageCount
                ));

        var visibleTags = adminView
                ? mistakeTagService.findAllForAdmin()
                : mistakeTagService.findAllVisibleToUser(currentUser.getId());

        var tableRows = visibleTags.stream()
                .map(tag -> new MistakeRowView(
                        tag,
                        usageByTagId.getOrDefault(tag.getId(), 0L),
                        adminView || isOwnedByCurrentUser(tag, currentUser),
                        resolveScopeLabel(tag, adminView)
                ))
                .toList();
        MistakeAnalyticsService.MistakeTrendReport trendReport = mistakeAnalyticsService.buildTrendReportForUser(currentUser.getId());

        long totalCount = tableRows.size();
        long activeCount = tableRows.stream().filter(MistakeRowView::active).count();
        long disabledCount = totalCount - activeCount;

        long sessionMaxUsage = tradeMistakeTagRepository.summarizeBySessionForUser(currentUser.getId())
                .stream()
                .mapToLong(TradeMistakeTagRepository.MistakeSessionRow::getUsageCount)
                .max()
                .orElse(0L);

        List<SessionUsageView> sessionUsage = tradeMistakeTagRepository.summarizeBySessionForUser(currentUser.getId())
                .stream()
                .limit(5)
                .map(item -> new SessionUsageView(
                        formatSessionLabel(item.getSession()),
                        item.getUsageCount(),
                        toPercent(item.getUsageCount(), sessionMaxUsage)
                ))
                .toList();

        long symbolMaxUsage = tradeMistakeTagRepository.summarizeBySymbolForUser(currentUser.getId())
                .stream()
                .mapToLong(TradeMistakeTagRepository.MistakeSymbolRow::getUsageCount)
                .max()
                .orElse(0L);

        List<SymbolUsageView> symbolUsage = tradeMistakeTagRepository.summarizeBySymbolForUser(currentUser.getId())
                .stream()
                .limit(5)
                .map(item -> new SymbolUsageView(
                        item.getSymbol(),
                        item.getUsageCount(),
                        toPercent(item.getUsageCount(), symbolMaxUsage)
                ))
                .toList();

        List<RecentMistakeView> recentMistakes = tradeMistakeTagRepository.findRecentMistakesForUser(currentUser.getId())
                .stream()
                .limit(5)
                .map(item -> new RecentMistakeView(
                        item.getTradeId(),
                        shortTradeRef(item.getTradeId()),
                        item.getMistakeName(),
                        formatSessionLabel(item.getSession()),
                        normalizeLabel(item.getSymbol()),
                        item.getEntryTime()
                ))
                .toList();

        model.addAttribute("mistakes", tableRows);
        model.addAttribute("topMistakes", trendReport.topMistakes());
        model.addAttribute("distributionMistakes", trendReport.distributionPoints().stream()
                .map(item -> new DistributionRowView(
                        item.label(),
                        item.usageCount(),
                        item.usagePercent(),
                        item.widthPercent()
                ))
                .toList());
        model.addAttribute("totalMistakeCount", totalCount);
        model.addAttribute("activeMistakeCount", activeCount);
        model.addAttribute("disabledMistakeCount", disabledCount);
        model.addAttribute("mostFrequentMistakeName", trendReport.mostFrequentMistakeName());
        model.addAttribute("mostFrequentMistakeUsage", trendReport.mostFrequentMistakeUsage());
        model.addAttribute("mostFrequentUsagePercent", trendReport.mostFrequentUsagePercent());
        model.addAttribute("mistakeQuickInsight", trendReport.quickInsight());
        model.addAttribute("sessionUsage", sessionUsage);
        model.addAttribute("symbolUsage", symbolUsage);
        model.addAttribute("recentMistakes", recentMistakes);

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
        if (!userService.isAdmin(currentUser)
                && mistakeTagService.countOwnedByUser(currentUser.getId()) >= userService.resolveMistakeTagLimit(currentUser)) {
            model.addAttribute("error", "Standard plan includes up to 10 custom mistake tags. Upgrade to Pro for more.");
        }

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
            if (userService.isAdmin(currentUser)) {
                mistakeTagService.createGlobal(code, name, description);
            } else {
                mistakeTagService.createForUser(code, name, description, currentUser);
            }
            return "redirect:/mistakes";
        } catch (IllegalArgumentException | IllegalStateException e) {
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

        try {
            model.addAttribute("currentUser", currentUser);
            model.addAttribute("mistake", userService.isAdmin(currentUser)
                    ? mistakeTagService.findByIdForAdmin(id)
                    : mistakeTagService.findByIdForUser(id, currentUser.getId()));
            model.addAttribute("editMode", true);
            return "mistakeForm";
        } catch (IllegalArgumentException ex) {
            return "redirect:/mistakes";
        }
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
            if (userService.isAdmin(currentUser)) {
                mistakeTagService.updateGlobal(id, code, name, description, active);
            } else {
                mistakeTagService.updateForUser(id, code, name, description, active, currentUser.getId());
            }
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

        try {
            if (userService.isAdmin(currentUser)) {
                mistakeTagService.toggleActiveGlobal(id);
            } else {
                mistakeTagService.toggleActiveForUser(id, currentUser.getId());
            }
        } catch (IllegalArgumentException ex) {
            return "redirect:/mistakes";
        }
        return "redirect:/mistakes";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        try {
            if (userService.isAdmin(currentUser)) {
                mistakeTagService.deleteGlobal(id);
            } else {
                mistakeTagService.deleteForUser(id, currentUser.getId());
            }
        } catch (IllegalArgumentException ex) {
            return "redirect:/mistakes";
        }
        return "redirect:/mistakes";
    }

    public record MistakeRowView(
            String id,
            String code,
            String name,
            String description,
            boolean active,
            long usageCount,
            boolean manageable,
            String scopeLabel
    ) {
        public MistakeRowView(MistakeTag tag, long usageCount, boolean manageable, String scopeLabel) {
            this(
                    tag.getId(),
                    tag.getCode(),
                    tag.getName(),
                    tag.getDescription(),
                    tag.isActive(),
                    usageCount,
                    manageable,
                    scopeLabel
            );
        }
    }

    public record DistributionRowView(String code, long usageCount, long usagePercent, long widthPercent) {}

    public record SessionUsageView(String label, long usageCount, long widthPercent) {}

    public record SymbolUsageView(String symbol, long usageCount, long widthPercent) {}

    public record RecentMistakeView(
            String tradeId,
            String tradeRef,
            String mistakeName,
            String session,
            String symbol,
            LocalDateTime entryTime
    ) {}

    private String shortTradeRef(String tradeId) {
        if (tradeId == null || tradeId.isBlank()) {
            return "N/A";
        }
        return tradeId.length() <= 8 ? tradeId : tradeId.substring(0, 8);
    }

    private String formatSessionLabel(String session) {
        if (session == null || session.isBlank()) {
            return "Unknown";
        }
        String normalized = session.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "NEW_YORK" -> "New York";
            case "LONDON" -> "London";
            case "ASIA" -> "Asia";
            case "UNKNOWN" -> "Unknown";
            default -> normalized.charAt(0) + normalized.substring(1).toLowerCase(Locale.ROOT);
        };
    }

    private String normalizeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown";
        }
        return value.trim();
    }

    private boolean isOwnedByCurrentUser(MistakeTag tag, User currentUser) {
        return tag != null
                && tag.getUser() != null
                && currentUser != null
                && currentUser.getId() != null
                && currentUser.getId().equals(tag.getUser().getId());
    }

    private String resolveScopeLabel(MistakeTag tag, boolean adminView) {
        if (tag == null) {
            return null;
        }
        if (tag.getUser() == null) {
            return adminView ? "System" : "System tag";
        }
        String owner = tag.getUser().getUsername();
        if (owner == null || owner.isBlank()) {
            owner = "User";
        }
        return adminView ? owner : "Private tag";
    }

    private long toPercent(long value, long total) {
        if (value <= 0 || total <= 0) {
            return 0L;
        }
        return Math.round((value * 100.0) / total);
    }
}
