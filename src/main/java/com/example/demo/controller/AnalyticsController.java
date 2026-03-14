package com.example.demo.controller;

import com.example.demo.entity.Trade;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.AnalyticsService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Controller
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final UserService userService;
    private final UserRepository userRepository;

    public AnalyticsController(AnalyticsService analyticsService, UserService userService, UserRepository userRepository) {
        this.analyticsService = analyticsService;
        this.userService = userService;
        this.userRepository = userRepository;
    }

    @GetMapping("/dashboard")
    public String overview(
            @RequestParam(value = "period", required = false, defaultValue = "ALL") String period,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "userId", required = false) String selectedUserId,
            Model model,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        boolean hasProAccess = userService.hasProAccess(currentUser);
        int tradeLimit = userService.resolveTradeLimit(currentUser);

        boolean adminView = userService.isAdmin(currentUser);
        List<User> managedUsers = adminView
                ? userRepository.findAll(Sort.by(Sort.Direction.ASC, "username"))
                : List.of();
        User targetUser = resolveTargetUser(currentUser, selectedUserId);

        ResolvedRange range = resolveRange(period, from, to);

        AnalyticsService.AnalyticsReport report = analyticsService.buildReportForUser(
                targetUser.getId(),
                range.fromDateTime(),
                range.toDateTime()
        );
        AnalyticsService.PeriodComparison comparison = analyticsService.buildPeriodComparisonForUser(
                targetUser.getId(),
                range.fromDateTime(),
                range.toDateTime()
        );

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("adminView", adminView);
        model.addAttribute("managedUsers", managedUsers);
        model.addAttribute("selectedUserId", targetUser.getId());
        model.addAttribute("analyticsTargetUser", targetUser);
        model.addAttribute("report", report);
        model.addAttribute("overview", report.getOverview());
        model.addAttribute("riskMetrics", report.getRiskMetrics());
        model.addAttribute("processMetrics", report.getProcessMetrics());
        model.addAttribute("comparison", comparison);
        model.addAttribute("period", range.period());
        model.addAttribute("from", range.fromDate());
        model.addAttribute("to", range.toDate());
        model.addAttribute("hasProAccess", hasProAccess);
        model.addAttribute("tradeUsage", hasProAccess ? report.getOverview().getTotalTrades() : Math.min(report.getOverview().getTotalTrades(), tradeLimit));
        model.addAttribute("tradeUsageLimit", hasProAccess ? 0 : tradeLimit);
        model.addAttribute("tradeLimitReached", !hasProAccess && report.getOverview().getTotalTrades() >= tradeLimit);

        return "dashboard";
    }

    @GetMapping(value = "/dashboard/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(value = "period", required = false, defaultValue = "ALL") String period,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "userId", required = false) String selectedUserId,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return ResponseEntity.status(401).build();
        }
        if (!userService.hasProAccess(currentUser)) {
            return ResponseEntity.status(403)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Pro feature required".getBytes());
        }
        User targetUser = resolveTargetUser(currentUser, selectedUserId);

        ResolvedRange range = resolveRange(period, from, to);
        AnalyticsService.AnalyticsReport report = analyticsService.buildReportForUser(
                targetUser.getId(),
                range.fromDateTime(),
                range.toDateTime()
        );
        List<Trade> trades = analyticsService.findTradesForUser(
                targetUser.getId(),
                range.fromDateTime(),
                range.toDateTime()
        );

        String csv = buildAnalyticsCsv(targetUser, range, report, trades);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "analytics_export_" + timestamp + ".csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv"))
                .body(csv.getBytes());
    }

    @GetMapping("/analytics")
    public String analyticsPage(
            @RequestParam(value = "period", required = false, defaultValue = "ALL") String period,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "setup", required = false) String setup,
            Model model,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        boolean hasProAccess = userService.hasProAccess(currentUser);
        int tradeLimit = userService.resolveTradeLimit(currentUser);

        ResolvedRange range = resolveRange(period, from, to);
        AnalyticsService.AnalyticsWorkspaceReport report = analyticsService.buildWorkspaceReportForUser(
                currentUser.getId(),
                range.fromDateTime(),
                range.toDateTime(),
                symbol,
                setup
        );
        int workspaceTradeCount = analyticsService.findTradesForUser(
                currentUser.getId(),
                range.fromDateTime(),
                range.toDateTime()
        ).size();

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("hasProAccess", hasProAccess);
        model.addAttribute("tradeUsage", hasProAccess ? workspaceTradeCount : Math.min(workspaceTradeCount, tradeLimit));
        model.addAttribute("tradeUsageLimit", hasProAccess ? 0 : tradeLimit);
        model.addAttribute("period", range.period());
        model.addAttribute("from", range.fromDate());
        model.addAttribute("to", range.toDate());
        model.addAttribute("selectedSymbol", normalizeOptionalFilter(symbol));
        model.addAttribute("selectedSetup", normalizeOptionalFilter(setup));
        model.addAttribute("analyticsReport", report);
        return "analytics";
    }

    @GetMapping("/reports")
    public String reportsPage(
            @RequestParam(value = "period", required = false, defaultValue = "ALL") String period,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "account", required = false) String account,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "setup", required = false) String setup,
            Model model,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        boolean hasProAccess = userService.hasProAccess(currentUser);
        int tradeLimit = userService.resolveTradeLimit(currentUser);

        ResolvedRange range = resolveRange(period, from, to);
        AnalyticsService.AnalyticsReport report = analyticsService.buildReportForUser(
                currentUser.getId(),
                range.fromDateTime(),
                range.toDateTime(),
                account,
                symbol,
                setup
        );
        AnalyticsService.AnalyticsWorkspaceReport workspaceReport = analyticsService.buildWorkspaceReportForUser(
                currentUser.getId(),
                range.fromDateTime(),
                range.toDateTime(),
                account,
                symbol,
                setup
        );
        List<Trade> filteredTrades = analyticsService.findTradesForUser(
                currentUser.getId(),
                range.fromDateTime(),
                range.toDateTime(),
                account,
                symbol,
                setup
        );

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("hasProAccess", hasProAccess);
        model.addAttribute("tradeUsage", hasProAccess ? filteredTrades.size() : Math.min(filteredTrades.size(), tradeLimit));
        model.addAttribute("tradeUsageLimit", hasProAccess ? 0 : tradeLimit);
        model.addAttribute("period", range.period());
        model.addAttribute("from", range.fromDate());
        model.addAttribute("to", range.toDate());
        model.addAttribute("selectedAccount", normalizeOptionalFilter(account));
        model.addAttribute("selectedSymbol", normalizeOptionalFilter(symbol));
        model.addAttribute("selectedSetup", normalizeOptionalFilter(setup));
        model.addAttribute("report", report);
        model.addAttribute("overview", report.getOverview());
        model.addAttribute("riskMetrics", report.getRiskMetrics());
        model.addAttribute("workspaceReport", workspaceReport);
        return "reports";
    }

    @GetMapping(value = "/reports/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportReportsCsv(
            @RequestParam(value = "period", required = false, defaultValue = "ALL") String period,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "account", required = false) String account,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "setup", required = false) String setup,
            HttpSession session
    ) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return ResponseEntity.status(401).build();
        }
        if (!userService.hasProAccess(currentUser)) {
            return ResponseEntity.status(403)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Pro feature required".getBytes());
        }

        ResolvedRange range = resolveRange(period, from, to);
        AnalyticsService.AnalyticsReport report = analyticsService.buildReportForUser(
                currentUser.getId(),
                range.fromDateTime(),
                range.toDateTime(),
                account,
                symbol,
                setup
        );
        List<Trade> trades = analyticsService.findTradesForUser(
                currentUser.getId(),
                range.fromDateTime(),
                range.toDateTime(),
                account,
                symbol,
                setup
        );

        String csv = buildAnalyticsCsv(currentUser, range, report, trades);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "reports_export_" + timestamp + ".csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv"))
                .body(csv.getBytes());
    }

    private String normalizePeriod(String period) {
        if (period == null || period.isBlank()) {
            return "ALL";
        }
        String normalized = period.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "7D", "30D", "90D", "CUSTOM", "ALL" -> normalized;
            default -> "ALL";
        };
    }

    private String normalizeOptionalFilter(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim())) {
            return "ALL";
        }
        return value.trim();
    }

    private User resolveTargetUser(User currentUser, String selectedUserId) {
        if (!userService.isAdmin(currentUser)) {
            return currentUser;
        }
        if (selectedUserId == null || selectedUserId.isBlank()) {
            return currentUser;
        }
        return userRepository.findById(selectedUserId).orElse(currentUser);
    }

    private ResolvedRange resolveRange(String period, LocalDate from, LocalDate to) {
        String selectedPeriod = normalizePeriod(period);
        LocalDate resolvedFrom = from;
        LocalDate resolvedTo = to;

        LocalDate today = LocalDate.now();
        if (!"CUSTOM".equals(selectedPeriod)) {
            resolvedFrom = null;
            resolvedTo = null;
            if ("7D".equals(selectedPeriod)) {
                resolvedFrom = today.minusDays(6);
                resolvedTo = today;
            } else if ("30D".equals(selectedPeriod)) {
                resolvedFrom = today.minusDays(29);
                resolvedTo = today;
            } else if ("90D".equals(selectedPeriod)) {
                resolvedFrom = today.minusDays(89);
                resolvedTo = today;
            }
        }

        if (resolvedFrom != null && resolvedTo != null && resolvedFrom.isAfter(resolvedTo)) {
            LocalDate temp = resolvedFrom;
            resolvedFrom = resolvedTo;
            resolvedTo = temp;
        }

        LocalDateTime fromDateTime = resolvedFrom != null ? resolvedFrom.atStartOfDay() : null;
        LocalDateTime toDateTime = resolvedTo != null ? resolvedTo.atTime(LocalTime.MAX) : null;
        return new ResolvedRange(selectedPeriod, resolvedFrom, resolvedTo, fromDateTime, toDateTime);
    }

    private String buildAnalyticsCsv(
            User currentUser,
            ResolvedRange range,
            AnalyticsService.AnalyticsReport report,
            List<Trade> trades
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Report,Value\n");
        sb.append(csv("Username")).append(",").append(csv(currentUser.getUsername())).append("\n");
        sb.append(csv("Period")).append(",").append(csv(range.period())).append("\n");
        sb.append(csv("From")).append(",").append(csv(range.fromDate() == null ? "" : range.fromDate().toString())).append("\n");
        sb.append(csv("To")).append(",").append(csv(range.toDate() == null ? "" : range.toDate().toString())).append("\n");
        sb.append("\n");

        AnalyticsService.TradeOverview overview = report.getOverview();
        sb.append("Overview,Value\n");
        sb.append(csv("Total Trades")).append(",").append(overview.getTotalTrades()).append("\n");
        sb.append(csv("Win Trades")).append(",").append(overview.getWinTrades()).append("\n");
        sb.append(csv("Loss Trades")).append(",").append(overview.getLossTrades()).append("\n");
        sb.append(csv("BE Trades")).append(",").append(overview.getBeTrades()).append("\n");
        sb.append(csv("Win Rate %")).append(",").append(overview.getWinRate()).append("\n");
        sb.append(csv("Total PnL")).append(",").append(overview.getTotalPnl()).append("\n");
        sb.append(csv("Avg PnL")).append(",").append(overview.getAvgPnl()).append("\n");
        sb.append(csv("Total R")).append(",").append(overview.getTotalR()).append("\n");
        sb.append(csv("Avg R")).append(",").append(overview.getAvgR()).append("\n");
        sb.append("\n");

        AnalyticsService.RiskMetrics risk = report.getRiskMetrics();
        sb.append("Risk Metrics,Value\n");
        sb.append(csv("Profit Factor")).append(",").append(csv(risk.getProfitFactor() == null ? "N/A" : String.valueOf(risk.getProfitFactor()))).append("\n");
        sb.append(csv("Max Drawdown")).append(",").append(risk.getMaxDrawdown()).append("\n");
        sb.append(csv("Expectancy")).append(",").append(risk.getExpectancy()).append("\n");
        sb.append(csv("Avg Win")).append(",").append(risk.getAvgWin()).append("\n");
        sb.append(csv("Avg Loss")).append(",").append(risk.getAvgLoss()).append("\n");
        sb.append("\n");

        AnalyticsService.ProcessMetrics process = report.getProcessMetrics();
        sb.append("Process Metrics,Value\n");
        sb.append(csv("Reviewed Trades")).append(",").append(process.getReviewedTrades()).append("\n");
        sb.append(csv("Reviewed Rate %")).append(",").append(process.getReviewedRate()).append("\n");
        sb.append(csv("High-Quality Trades")).append(",").append(process.getHighQualityTrades()).append("\n");
        sb.append(csv("Winrate High-Quality %")).append(",").append(process.getHighQualityWinRate()).append("\n");
        sb.append(csv("Avg R Followed Plan")).append(",").append(process.getAvgRFollowedPlan()).append("\n");
        sb.append(csv("Bad Process Wins")).append(",").append(process.getBadProcessWins()).append("\n");
        sb.append(csv("Grade A Trades")).append(",").append(process.getGradeA()).append("\n");
        sb.append(csv("Grade B Trades")).append(",").append(process.getGradeB()).append("\n");
        sb.append(csv("Grade C Trades")).append(",").append(process.getGradeC()).append("\n");
        sb.append(csv("Good Process, Good Outcome")).append(",").append(process.getGoodProcessGoodOutcome()).append("\n");
        sb.append(csv("Good Process, Bad Outcome")).append(",").append(process.getGoodProcessBadOutcome()).append("\n");
        sb.append(csv("Bad Process, Good Outcome")).append(",").append(process.getBadProcessGoodOutcome()).append("\n");
        sb.append(csv("Bad Process, Bad Outcome")).append(",").append(process.getBadProcessBadOutcome()).append("\n");
        sb.append("\n");

        sb.append("Trades\n");
        sb.append("Id,Entry Time,Symbol,Direction,Setup,Session,Result,PnL,R Multiple,R Source,Account\n");
        for (Trade trade : trades) {
            sb.append(csv(trade.getId())).append(",")
                    .append(csv(formatDateTime(trade.getEntryTime()))).append(",")
                    .append(csv(trade.getSymbol())).append(",")
                    .append(csv(trade.getDirection())).append(",")
                    .append(csv(trade.getSetupName())).append(",")
                    .append(csv(trade.getSession())).append(",")
                    .append(csv(trade.getResult())).append(",")
                    .append(trade.getPnl()).append(",")
                    .append(csv(trade.hasKnownRMultiple() ? String.valueOf(trade.getRMultiple()) : "")).append(",")
                    .append(csv(trade.getRMultipleSourceLabel())).append(",")
                    .append(csv(trade.getAccountLabel()))
                    .append("\n");
        }
        return sb.toString();
    }

    private String formatDateTime(LocalDateTime value) {
        if (value == null) {
            return "";
        }
        return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private String csv(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private record ResolvedRange(
            String period,
            LocalDate fromDate,
            LocalDate toDate,
            LocalDateTime fromDateTime,
            LocalDateTime toDateTime
    ) {
    }
}
