package com.example.demo.controller;

import com.example.demo.entity.PlanType;
import com.example.demo.entity.Setup;
import com.example.demo.entity.Trade;
import com.example.demo.entity.TradeMistakeTag;
import com.example.demo.entity.User;
import com.example.demo.repository.SetupRepository;
import com.example.demo.repository.TradeMistakeTagRepository;
import com.example.demo.repository.TradeRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/reports")
public class AdminReportsController {
    private final UserRepository userRepository;
    private final TradeRepository tradeRepository;
    private final SetupRepository setupRepository;
    private final TradeMistakeTagRepository tradeMistakeTagRepository;
    private final UserService userService;

    public AdminReportsController(
            UserRepository userRepository,
            TradeRepository tradeRepository,
            SetupRepository setupRepository,
            TradeMistakeTagRepository tradeMistakeTagRepository,
            UserService userService
    ) {
        this.userRepository = userRepository;
        this.tradeRepository = tradeRepository;
        this.setupRepository = setupRepository;
        this.tradeMistakeTagRepository = tradeMistakeTagRepository;
        this.userService = userService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public String reports(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String setup,
            @RequestParam(required = false) String sessionFilter,
            @RequestParam(required = false) String resultFilter,
            Model model,
            HttpSession session
    ) {
        User admin = userService.getCurrentUser(session);
        if (admin == null) return "redirect:/login";
        if (!userService.isAdmin(admin)) return "redirect:/trades";

        LocalDate today = LocalDate.now();
        LocalDate selectedFrom = from != null ? from : today.minusDays(29);
        LocalDate selectedTo = to != null ? to : today;
        String selectedUserId = norm(userId);
        String selectedSymbol = norm(symbol);
        String selectedSetup = norm(setup);
        String selectedSession = normSessionFilter(sessionFilter);
        String selectedResult = normResultFilter(resultFilter);

        List<User> users = userRepository.findAll(Sort.by(Sort.Direction.ASC, "username")).stream()
                .filter(u -> u.getRole() == null || !"ADMIN".equalsIgnoreCase(u.getRole()))
                .toList();
        List<Trade> allTrades = tradeRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Trade> filteredTrades = allTrades.stream()
                .filter(t -> t.getUser() == null || t.getUser().getRole() == null || !"ADMIN".equalsIgnoreCase(t.getUser().getRole()))
                .filter(t -> matchDate(t, selectedFrom, selectedTo))
                .filter(t -> selectedUserId.isEmpty() || (t.getUser() != null && selectedUserId.equals(t.getUser().getId())))
                .filter(t -> selectedSymbol.isEmpty() || selectedSymbol.equalsIgnoreCase(normLabel(t.getSymbol())))
                .filter(t -> selectedSetup.isEmpty() || selectedSetup.equalsIgnoreCase(normLabel(t.getSetupName())))
                .filter(t -> "ALL".equals(selectedSession) || selectedSession.equals(normSession(t.getSession())))
                .filter(t -> "ALL".equals(selectedResult) || selectedResult.equals(normResult(t.getResult())))
                .toList();

        Set<String> tradeIds = filteredTrades.stream().map(Trade::getId).collect(Collectors.toSet());
        List<TradeMistakeTag> mistakeLinks = tradeMistakeTagRepository.findAll().stream()
                .filter(link -> link.getTrade() != null && tradeIds.contains(link.getTrade().getId()))
                .toList();

        model.addAttribute("currentUser", admin);
        model.addAttribute("reportSections", sections());
        model.addAttribute("reportPreviews", previews(filteredTrades, users, mistakeLinks, selectedFrom, selectedTo));
        model.addAttribute("selectedFrom", selectedFrom);
        model.addAttribute("selectedTo", selectedTo);
        model.addAttribute("selectedUserId", selectedUserId);
        model.addAttribute("selectedSymbol", selectedSymbol);
        model.addAttribute("selectedSetup", selectedSetup);
        model.addAttribute("selectedSession", selectedSession);
        model.addAttribute("selectedResult", selectedResult);
        model.addAttribute("userOptions", users.stream().map(u -> new FilterOption(u.getId(), u.getUsername())).toList());
        model.addAttribute("symbolOptions", allTrades.stream().map(Trade::getSymbol).filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        model.addAttribute("setupOptions", setupRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).stream().map(Setup::getName).filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).distinct().toList());
        model.addAttribute("sessionOptions", List.of(new FilterOption("ALL", "All sessions"), new FilterOption("LONDON", "London"), new FilterOption("NEW_YORK", "New York"), new FilterOption("ASIA", "Asia")));
        model.addAttribute("resultOptions", List.of(new FilterOption("ALL", "All"), new FilterOption("WIN", "Win"), new FilterOption("LOSS", "Loss"), new FilterOption("BREAKEVEN", "Break-even")));
        return "adminReports";
    }

    private Map<String, ReportPreview> previews(List<Trade> trades, List<User> users, List<TradeMistakeTag> links, LocalDate from, LocalDate to) {
        Map<String, ReportPreview> map = new LinkedHashMap<>();
        TradeStats all = stats(trades);
        long proUsers = users.stream().filter(u -> u.getPlanType() == PlanType.PRO).count();
        long stdUsers = users.stream().filter(u -> u.getPlanType() == null || u.getPlanType() == PlanType.STANDARD).count();
        double conversionRate = users.isEmpty() ? 0.0 : (proUsers * 100.0 / users.size());

        map.put("platform-overview", preview(kv("Date range", from + " to " + to), kv("Trades", String.valueOf(all.trades)), kv("Winrate", pct(all.winRate)), kv("Avg R", num(all.avgR) + "R")));
        map.put("revenue-plan-mix", preview(kv("Standard users", String.valueOf(stdUsers)), kv("Pro users", String.valueOf(proUsers)), kv("Conversion rate", pct(conversionRate)), kv("Monthly revenue", money(proUsers * 29.0))));
        map.put("system-health-summary", preview(kv("Disabled accounts", String.valueOf(users.stream().filter(u -> !u.isActive()).count())), kv("Suspicious users", String.valueOf(suspiciousUsers(trades))), kv("Trades in period", String.valueOf(all.trades)), kv("Last refresh", LocalDateTime.now().withSecond(0).withNano(0).toString().replace('T', ' '))));
        map.put("trading-performance", preview(kv("Trades", String.valueOf(all.trades)), kv("Winrate", pct(all.winRate)), kv("Avg R", num(all.avgR) + "R"), kv("Total PnL", moneySigned(all.pnl))));

        TradeStats setupStats = stats(trades.stream().filter(t -> normLabel(t.getSetupName()).equals(topValue(trades, true))).toList());
        map.put("setup-performance", preview(kv("Top setup", topValue(trades, true)), kv("Trades", String.valueOf(setupStats.trades)), kv("Winrate", pct(setupStats.winRate)), kv("Avg R", num(setupStats.avgR) + "R"), kv("Expectancy", num(setupStats.expectancy) + "R"), kv("PnL", moneySigned(setupStats.pnl))));

        TradeStats symbolStats = stats(trades.stream().filter(t -> normLabel(t.getSymbol()).equals(topValue(trades, false))).toList());
        map.put("symbol-analytics", preview(kv("Top symbol", topValue(trades, false)), kv("Trades", String.valueOf(symbolStats.trades)), kv("Winrate", pct(symbolStats.winRate)), kv("Avg R", num(symbolStats.avgR) + "R"), kv("PnL", moneySigned(symbolStats.pnl))));

        map.put("mistake-impact", mistakeImpactPreview(links));
        map.put("user-activity-report", preview(kv("Active users", String.valueOf(trades.stream().map(Trade::getUser).filter(Objects::nonNull).map(User::getId).distinct().count())), kv("Avg trades/user", num(avgTradesPerUser(trades))), kv("Winrate", pct(all.winRate)), kv("Total PnL", moneySigned(all.pnl))));
        map.put("top-traders", preview(kv("Top trader", topTrader(trades).name), kv("Trades", String.valueOf(topTrader(trades).trades)), kv("Winrate", pct(topTrader(trades).winRate)), kv("PnL", moneySigned(topTrader(trades).pnl))));
        map.put("at-risk-accounts", preview(kv("Accounts flagged", String.valueOf(suspiciousUsers(trades))), kv("Loss-heavy users", String.valueOf(lossHeavyUsers(trades))), kv("Low activity users", String.valueOf(lowActivityUsers(trades))), kv("Scope trades", String.valueOf(all.trades))));
        map.put("export-trades", preview(kv("Trades ready", String.valueOf(trades.size())), kv("Columns", "Symbol, setup, result, pnl, r, session"), kv("Date scope", from + " to " + to), kv("Format", "CSV / Excel")));
        map.put("export-users", preview(kv("Users in scope", String.valueOf(trades.stream().map(Trade::getUser).filter(Objects::nonNull).map(User::getId).distinct().count())), kv("Trader coverage", String.valueOf(users.size()) + " users"), kv("Top trader", topTrader(trades).name), kv("Format", "CSV / Excel")));
        map.put("export-setups", preview(kv("Unique setups", String.valueOf(trades.stream().map(Trade::getSetupName).filter(Objects::nonNull).distinct().count())), kv("Best setup", topValue(trades, true)), kv("Best setup expectancy", num(setupStats.expectancy) + "R"), kv("Format", "CSV / Excel")));
        map.put("export-mistakes", preview(kv("Mistake tags", String.valueOf(links.stream().map(link -> link.getMistakeTag() != null ? link.getMistakeTag().getName() : "N/A").distinct().count())), kv("Tagged trades", String.valueOf(links.size())), kv("Most costly mistake", worstMistakeName(links)), kv("Format", "CSV / Excel")));
        return map;
    }

    private ReportPreview mistakeImpactPreview(List<TradeMistakeTag> links) {
        Map<String, List<Trade>> byMistake = links.stream()
                .filter(link -> link.getMistakeTag() != null && link.getTrade() != null)
                .collect(Collectors.groupingBy(link -> normLabel(link.getMistakeTag().getName()), LinkedHashMap::new, Collectors.mapping(TradeMistakeTag::getTrade, Collectors.toList())));
        List<List<String>> rows = byMistake.entrySet().stream()
                .map(entry -> {
                    TradeStats s = stats(entry.getValue());
                    return List.of(entry.getKey(), String.valueOf(s.trades), pct(s.winRate), num(s.avgR) + "R", moneySigned(s.pnl));
                })
                .sorted((a, b) -> Double.compare(parseMoney(a.get(4)), parseMoney(b.get(4))))
                .limit(5)
                .toList();
        return new ReportPreview(
                List.of(kv("Tagged trades", String.valueOf(links.size())), kv("Most costly mistake", rows.isEmpty() ? "N/A" : rows.get(0).get(0)), kv("PnL impact", rows.isEmpty() ? "$0.00" : rows.get(0).get(4)), kv("Avg tagged R", rows.isEmpty() ? "0.00R" : rows.get(0).get(3))),
                List.of("Mistake", "Trades", "Winrate", "Avg R", "PnL Impact"),
                rows
        );
    }

    private List<ReportSection> sections() {
        return List.of(
                new ReportSection("Platform Reports", "tint-platform", List.of(new ReportCard("platform-overview", "Platform Overview", "Snapshot of platform growth, engagement, subscriptions, and system health indicators."), new ReportCard("revenue-plan-mix", "Revenue & Plan Mix", "Standard users, pro users, conversion rate, and monthly revenue projection."), new ReportCard("system-health-summary", "System Health Summary", "Operational metrics, disabled accounts, and behavior risk overview."))),
                new ReportSection("Trading Reports", "tint-trading", List.of(new ReportCard("trading-performance", "Trading Performance", "Aggregate trades, winrate, R multiple, and total PnL across the selected scope."), new ReportCard("setup-performance", "Setup Performance", "Setup quality with trades, winrate, Avg R, Expectancy, and PnL."), new ReportCard("symbol-analytics", "Symbol Analytics", "Most traded symbols with session edge and outcome quality."), new ReportCard("mistake-impact", "Mistake Impact Report", "Mistake-level impact on winrate, Avg R, and PnL to identify repeat execution leaks."))),
                new ReportSection("User Reports", "tint-user", List.of(new ReportCard("user-activity-report", "User Activity Report", "User-level activity, trade participation, and engagement scorecard."), new ReportCard("top-traders", "Top Traders", "Highest activity traders with performance quality metrics."), new ReportCard("at-risk-accounts", "At-Risk Accounts", "Users with inactivity, loss concentration, or behavior anomalies."))),
                new ReportSection("Export Center", "tint-export", List.of(new ReportCard("export-trades", "Export Trades", "Filtered trading records prepared for external analysis."), new ReportCard("export-users", "Export Users", "User activity and profile-level analytics extract."), new ReportCard("export-setups", "Export Setups", "Setup performance and expectancy snapshot export."), new ReportCard("export-mistakes", "Export Mistakes", "Mistake-frequency and impact dataset export.")))
        );
    }

    private TradeStats stats(List<Trade> trades) {
        if (trades.isEmpty()) return new TradeStats(0, 0, 0, 0, 0);
        long total = trades.size();
        long wins = trades.stream().filter(t -> "WIN".equals(normResult(t.getResult()))).count();
        long losses = trades.stream().filter(t -> "LOSS".equals(normResult(t.getResult()))).count();
        double avgR = trades.stream().mapToDouble(Trade::getRMultiple).average().orElse(0);
        double pnl = trades.stream().mapToDouble(Trade::getPnl).sum();
        double avgWinR = trades.stream().filter(t -> "WIN".equals(normResult(t.getResult()))).mapToDouble(Trade::getRMultiple).average().orElse(0);
        double avgLossR = trades.stream().filter(t -> "LOSS".equals(normResult(t.getResult()))).mapToDouble(Trade::getRMultiple).average().orElse(0);
        double expectancy = (wins * 1.0 / total) * avgWinR + (losses * 1.0 / total) * avgLossR;
        return new TradeStats(total, wins * 100.0 / total, avgR, pnl, expectancy);
    }

    private boolean matchDate(Trade t, LocalDate from, LocalDate to) {
        LocalDateTime at = t.getCreatedAt() != null ? t.getCreatedAt() : (t.getEntryTime() != null ? t.getEntryTime() : t.getTradeDate());
        if (at == null) return false;
        LocalDate d = at.toLocalDate();
        return !d.isBefore(from) && !d.isAfter(to);
    }

    private String topValue(List<Trade> trades, boolean setup) {
        return trades.stream().collect(Collectors.groupingBy(t -> setup ? normLabel(t.getSetupName()) : normLabel(t.getSymbol()), Collectors.counting())).entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("N/A");
    }

    private TopTrader topTrader(List<Trade> trades) {
        return trades.stream().filter(t -> t.getUser() != null).collect(Collectors.groupingBy(t -> t.getUser().getUsername())).entrySet().stream().map(entry -> {
            TradeStats s = stats(entry.getValue());
            return new TopTrader(entry.getKey(), s.trades, s.winRate, s.pnl);
        }).max((a, b) -> Long.compare(a.trades, b.trades)).orElse(new TopTrader("N/A", 0, 0, 0));
    }

    private int suspiciousUsers(List<Trade> trades) { return userRiskCount(trades, (c, l) -> c >= 8 && l * 100.0 / c >= 75); }
    private int lossHeavyUsers(List<Trade> trades) { return userRiskCount(trades, (c, l) -> c >= 5 && l * 100.0 / c >= 70); }
    private int lowActivityUsers(List<Trade> trades) { return (int) trades.stream().filter(t -> t.getUser() != null).collect(Collectors.groupingBy(t -> t.getUser().getId(), Collectors.counting())).values().stream().filter(c -> c <= 2).count(); }
    private int userRiskCount(List<Trade> trades, RiskRule rule) { return (int) trades.stream().filter(t -> t.getUser() != null).collect(Collectors.groupingBy(t -> t.getUser().getId())).values().stream().filter(ts -> { long c = ts.size(); long l = ts.stream().filter(t -> "LOSS".equals(normResult(t.getResult()))).count(); return rule.match(c, l); }).count(); }
    private double avgTradesPerUser(List<Trade> trades) { Map<String, Long> by = trades.stream().filter(t -> t.getUser() != null).collect(Collectors.groupingBy(t -> t.getUser().getId(), Collectors.counting())); return by.isEmpty() ? 0 : by.values().stream().mapToLong(Long::longValue).average().orElse(0); }
    private String worstMistakeName(List<TradeMistakeTag> links) { return links.stream().filter(l -> l.getMistakeTag() != null).collect(Collectors.groupingBy(l -> normLabel(l.getMistakeTag().getName()), Collectors.counting())).entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("N/A"); }

    private String norm(String v) { return v == null || v.isBlank() ? "" : v.trim(); }
    private String normLabel(String v) { return v == null || v.isBlank() ? "N/A" : v.trim(); }
    private String normSessionFilter(String v) { String s = (v == null || v.isBlank()) ? "ALL" : v.trim().toUpperCase(Locale.ROOT); return Set.of("ALL", "LONDON", "NEW_YORK", "ASIA").contains(s) ? s : "ALL"; }
    private String normResultFilter(String v) { String s = (v == null || v.isBlank()) ? "ALL" : v.trim().toUpperCase(Locale.ROOT); return Set.of("ALL", "WIN", "LOSS", "BREAKEVEN").contains(s) ? s : "ALL"; }
    private String normResult(String v) { String s = v == null ? "" : v.trim().toUpperCase(Locale.ROOT); if ("WIN".equals(s)) return "WIN"; if ("LOSS".equals(s)) return "LOSS"; return "BREAKEVEN"; }
    private String normSession(String v) { String s = v == null ? "" : v.trim().toUpperCase(Locale.ROOT); if ("LONDON".equals(s) || "NEW_YORK".equals(s) || "ASIA".equals(s)) return s; return "OTHER"; }
    private String pct(double v) { return num(v) + "%"; }
    private String num(double v) { return String.format(Locale.ENGLISH, "%,.2f", v); }
    private String money(double v) { return "$" + String.format(Locale.ENGLISH, "%,.2f", v); }
    private String moneySigned(double v) { return (v >= 0 ? "+$" : "-$") + String.format(Locale.ENGLISH, "%,.2f", Math.abs(v)); }
    private double parseMoney(String s) { try { return Double.parseDouble(s.replace(",", "").replace("+$", "").replace("$", "")); } catch (Exception ex) { return 0; } }
    private ReportPreview preview(MetricItem... metrics) { return new ReportPreview(List.of(metrics), List.of(), List.of()); }
    private MetricItem kv(String label, String value) { return new MetricItem(label, value); }

    @FunctionalInterface private interface RiskRule { boolean match(long count, long losses); }
    private record TradeStats(long trades, double winRate, double avgR, double pnl, double expectancy) {}
    private record TopTrader(String name, long trades, double winRate, double pnl) {}
    private record FilterOption(String value, String label) {}
    private record ReportSection(String title, String tintClass, List<ReportCard> cards) {}
    private record ReportCard(String key, String title, String description) {}
    private record MetricItem(String label, String value) {}
    private record ReportPreview(List<MetricItem> metrics, List<String> tableHeaders, List<List<String>> tableRows) {}
}
