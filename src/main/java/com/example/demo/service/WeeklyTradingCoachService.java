package com.example.demo.service;

import com.example.demo.entity.Trade;
import com.example.demo.entity.TradeMistakeTag;
import com.example.demo.entity.TradeReview;
import com.example.demo.repository.TradeMistakeTagRepository;
import com.example.demo.repository.TradeReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class WeeklyTradingCoachService {

    private static final DateTimeFormatter DATE_LABEL_FORMATTER = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    private final TradeService tradeService;
    private final TradeReviewRepository tradeReviewRepository;
    private final TradeMistakeTagRepository tradeMistakeTagRepository;

    public WeeklyTradingCoachService(
            TradeService tradeService,
            TradeReviewRepository tradeReviewRepository,
            TradeMistakeTagRepository tradeMistakeTagRepository
    ) {
        this.tradeService = tradeService;
        this.tradeReviewRepository = tradeReviewRepository;
        this.tradeMistakeTagRepository = tradeMistakeTagRepository;
    }

    @Transactional(readOnly = true)
    public WeeklyCoachReport buildReportForUser(String userId) {
        return buildReportForUser(userId, LocalDate.now());
    }

    @Transactional(readOnly = true)
    public WeeklyCoachReport buildReportForUser(String userId, LocalDate periodEndDate) {
        LocalDate endDate = periodEndDate == null ? LocalDate.now() : periodEndDate;
        LocalDate startDate = endDate.minusDays(6);
        LocalDateTime currentFrom = startDate.atStartOfDay();
        LocalDateTime currentTo = endDate.atTime(LocalTime.MAX);

        LocalDate previousEndDate = startDate.minusDays(1);
        LocalDate previousStartDate = previousEndDate.minusDays(6);
        LocalDateTime previousFrom = previousStartDate.atStartOfDay();
        LocalDateTime previousTo = previousEndDate.atTime(LocalTime.MAX);

        List<Trade> allTrades = tradeService.findAllByUser(userId);
        WeekSnapshot currentWeek = buildWeekSnapshot(
                startDate,
                endDate,
                filterTradesByRange(allTrades, currentFrom, currentTo)
        );
        WeekSnapshot previousWeek = buildWeekSnapshot(
                previousStartDate,
                previousEndDate,
                filterTradesByRange(allTrades, previousFrom, previousTo)
        );

        if (currentWeek.totalTrades == 0) {
            return buildEmptyReport(currentWeek, previousWeek);
        }

        List<String> keyFindings = buildKeyFindings(currentWeek, previousWeek);
        List<CoachIssue> topMistakes = buildTopMistakes(currentWeek);
        List<String> improvements = buildImprovements(currentWeek);
        List<String> nextWeekPlan = buildNextWeekPlan(currentWeek, previousWeek);

        return new WeeklyCoachReport(
                currentWeek.startDate,
                currentWeek.endDate,
                currentWeek.totalTrades,
                currentWeek.avgProcessScore == null ? null : (int) Math.round(currentWeek.avgProcessScore),
                currentWeek.reviewedTrades,
                currentWeek.manualReviewedTrades,
                currentWeek.symbolCount,
                currentWeek.sessionCount,
                false,
                buildSummary(currentWeek, previousWeek),
                keyFindings,
                topMistakes,
                improvements,
                nextWeekPlan,
                buildConfidenceNote(currentWeek)
        );
    }

    private WeeklyCoachReport buildEmptyReport(WeekSnapshot currentWeek, WeekSnapshot previousWeek) {
        List<String> keyFindings = new ArrayList<>();
        keyFindings.add("No trades were logged in this 7-day window, so there is nothing to grade for execution quality.");
        if (previousWeek.totalTrades > 0) {
            keyFindings.add("The previous 7-day window had activity, so this was a stop week rather than a missing-history problem.");
        }
        keyFindings.add("Use the downtime to review recent losing clusters and tighten next-week rules before size goes back on.");

        List<CoachIssue> topMistakes = List.of(
                new CoachIssue(
                        1,
                        "No fresh execution sample",
                        "Without any trades this week, there is no current process evidence to coach against."
                )
        );

        return new WeeklyCoachReport(
                currentWeek.startDate,
                currentWeek.endDate,
                0,
                null,
                0,
                0,
                0,
                0,
                true,
                "No trades were recorded in this weekly window, so the problem is not performance quality yet. The problem is lack of fresh evidence. Do not treat inactivity as improvement unless it came from a deliberate reset and review.",
                keyFindings,
                topMistakes,
                List.of(
                        "Review the last losing streak before you place the next live trade.",
                        "Write the exact setup and invalidation rules you want to enforce next week.",
                        "Do not return to market just because you feel inactive. Return when the playbook is clear."
                ),
                List.of(
                        "Trade only one market and one primary setup until you rebuild a fresh data sample.",
                        "Complete a pre-trade checklist before every live order.",
                        "Journal every trade with a post-trade review on the same day."
                ),
                "No weekly trade sample available."
        );
    }

    private String buildSummary(WeekSnapshot currentWeek, WeekSnapshot previousWeek) {
        StringBuilder summary = new StringBuilder();
        summary.append("For ")
                .append(formatRange(currentWeek.startDate, currentWeek.endDate))
                .append(", ");

        if (currentWeek.avgProcessScore == null) {
            summary.append("the outcomes are harder to trust because your review evidence is thin. ");
        } else if (currentWeek.avgProcessScore >= 85.0) {
            summary.append("execution quality was strong and selective. ");
        } else if (currentWeek.avgProcessScore >= 70.0) {
            summary.append("execution quality was decent, but still short of elite discipline. ");
        } else {
            summary.append("execution quality was loose, even if parts of the week still finished green. ");
        }

        if (previousWeek.totalTrades > 0 && currentWeek.totalTrades < previousWeek.totalTrades && currentWeek.totalPnl >= previousWeek.totalPnl) {
            summary.append("The improvement came from restraint more than aggression. ");
        }

        if (currentWeek.manualReviewGapTrades > 0) {
            summary.append("Your main bad habit is still under-reviewing trades, which makes a good week look cleaner than it is.");
        } else if (currentWeek.lowReturnWins > 0) {
            summary.append("The main leak was payoff capture: at least one winner paid too little for the read.");
        } else if (currentWeek.totalTrades < 5) {
            summary.append("The sample is clean, but it is still too small to call the process fully fixed.");
        } else {
            summary.append("The week was cleaner than average, but the next test is whether you can repeat it without expanding too fast.");
        }
        return summary.toString();
    }

    private List<String> buildKeyFindings(WeekSnapshot currentWeek, WeekSnapshot previousWeek) {
        List<String> findings = new ArrayList<>();

        if (currentWeek.avgProcessScore == null) {
            findings.add("Process quality is not truly measurable this week because too many trades lack meaningful review data.");
        } else if (currentWeek.avgProcessScore >= 85.0) {
            findings.add("Execution quality was strong. You traded in a way that looks deliberate rather than reactive.");
        } else if (currentWeek.avgProcessScore >= 70.0) {
            findings.add("Execution was solid, not elite. You still have slippage between a profitable week and a professional week.");
        } else {
            findings.add("The process was weak. Any green result would be flattering bad habits rather than proving edge.");
        }

        if (previousWeek.totalTrades > 0 && currentWeek.totalTrades < previousWeek.totalTrades) {
            findings.add("The cleaner week came with lower volume. That is a real signal: restraint is helping you more than activity.");
        } else if (currentWeek.totalTrades > 10) {
            findings.add("Trade frequency was high enough to risk volume creep. When your pace rises, your discipline usually gets diluted.");
        }

        if (currentWeek.symbolCount == 1) {
            findings.add("Consistency improved because you stayed focused on one market instead of scattering attention.");
        } else if (currentWeek.symbolCount >= 3) {
            findings.add("Your attention was spread across too many symbols for a week that is supposed to prove consistency.");
        } else {
            findings.add("Instrument selection was only partly consistent. You are closer to focused, but not fully locked in.");
        }

        if (currentWeek.manualReviewGapTrades > 0) {
            findings.add("You are still logging outcomes better than decisions. Missing review detail is a process problem, not an admin problem.");
        } else {
            findings.add("Review discipline held up. The week has enough audit trail to judge behavior, not just outcome.");
        }

        if (currentWeek.lowReturnWins > 0) {
            findings.add("At least one winner paid under 1R. If that was not a planned partial, your exit discipline is still too emotional.");
        } else if (currentWeek.allWins && currentWeek.totalTrades > 0) {
            findings.add("An all-green week can still create outcome bias. Do not let good results lower your review standards.");
        } else if (!currentWeek.mistakeCounts.isEmpty()) {
            findings.add("The same mistakes are still repeating. Until the top leak stops showing up, the edge is not clean.");
        }

        return findings.stream()
                .filter(StringUtils::hasText)
                .limit(5)
                .toList();
    }

    private List<CoachIssue> buildTopMistakes(WeekSnapshot currentWeek) {
        List<CoachIssue> issues = new ArrayList<>();
        int rank = 1;

        if (currentWeek.manualReviewGapTrades > 0) {
            issues.add(new CoachIssue(
                    rank++,
                    "Incomplete self-review",
                    currentWeek.manualReviewGapTrades + " of " + currentWeek.totalTrades + " trades lack enough checklist evidence to verify the process."
            ));
        }

        if (currentWeek.lowReturnWins > 0) {
            issues.add(new CoachIssue(
                    rank++,
                    "Weak payoff capture",
                    currentWeek.lowReturnWins + " winner" + pluralize(currentWeek.lowReturnWins) + " closed below 1R. That is only acceptable when it was planned in advance."
            ));
        }

        for (Map.Entry<String, Integer> entry : currentWeek.mistakeCounts.entrySet()) {
            if (issues.size() >= 3) {
                break;
            }
            issues.add(new CoachIssue(
                    rank++,
                    entry.getKey(),
                    "Repeated across " + entry.getValue() + " trade" + pluralize(entry.getValue()) + " this week."
            ));
        }

        if (issues.size() < 3 && currentWeek.allWins && currentWeek.totalTrades > 0) {
            issues.add(new CoachIssue(
                    rank,
                    "Outcome bias risk",
                    "A fully green week can hide weak habits if you stop grading yourself on decision quality."
            ));
        } else if (issues.isEmpty()) {
            issues.add(new CoachIssue(
                    rank,
                    "Small sample overconfidence",
                    "The week was clean, but the sample is too small to claim the behavior is fully stable."
            ));
        }

        return issues;
    }

    private List<String> buildImprovements(WeekSnapshot currentWeek) {
        List<String> improvements = new ArrayList<>();

        if (currentWeek.manualReviewGapTrades > 0) {
            improvements.add("Make the post-trade review mandatory before a trade counts as closed.");
        }
        if (currentWeek.lowReturnWins > 0) {
            improvements.add("Force exit accountability: every winner under 1R must be tagged as either planned partial or premature exit.");
        }

        if (containsMistake(currentWeek, "FOMO entry") || containsMistake(currentWeek, "Entered without confirmation")) {
            improvements.add("Tighten entry rules and remove any trade that does not have explicit confirmation written before entry.");
        } else if (currentWeek.symbolCount > 1) {
            improvements.add("Narrow next week back to one primary market so execution stays repeatable.");
        } else {
            improvements.add("Do not expand size or playbook just because this week felt cleaner.");
        }

        return improvements.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .limit(3)
                .toList();
    }

    private List<String> buildNextWeekPlan(WeekSnapshot currentWeek, WeekSnapshot previousWeek) {
        List<String> plan = new ArrayList<>();

        String topSymbol = currentWeek.primarySymbol;
        if (StringUtils.hasText(topSymbol) && !"N/A".equalsIgnoreCase(topSymbol)) {
            plan.add("Trade only " + topSymbol + " unless a second market clearly matches the same A-grade setup.");
        } else {
            plan.add("Trade only one primary market next week until your review quality stays consistent.");
        }

        String topSetup = currentWeek.primarySetup;
        if (StringUtils.hasText(topSetup) && !"N/A".equalsIgnoreCase(topSetup)) {
            plan.add("Take only " + topSetup + " setups unless you can write a clear reason for breaking that rule before entry.");
        } else {
            plan.add("Limit yourself to your highest-conviction setup family instead of experimenting mid-week.");
        }

        int dailyTradeCap = currentWeek.totalTrades >= 8 || previousWeek.totalTrades >= 8 ? 3 : 2;
        plan.add("Max " + dailyTradeCap + " live trades per day. If you hit the cap, the rest of the work is review, not more execution.");
        plan.add("Complete the post-trade review within 10 minutes of exit. If a trade is not reviewed, no next trade.");

        if (currentWeek.lowReturnWins > 0) {
            plan.add("Any winner below 1R must be labeled immediately as planned partial or premature exit.");
        } else if (containsMistake(currentWeek, "Risk not respected")) {
            plan.add("If you break risk rules once, cut the next position size by 50 percent. Break it twice and stop for the day.");
        } else {
            plan.add("If you break one process rule, size down. If you break two in one day, stop trading for the day.");
        }

        return plan.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .limit(5)
                .toList();
    }

    private String buildConfidenceNote(WeekSnapshot currentWeek) {
        if (currentWeek.totalTrades < 3) {
            return "Low sample week. Read this as coaching guidance, not proof that the process is fixed.";
        }
        if (currentWeek.manualReviewedTrades == 0) {
            return "Low confidence because there is almost no structured self-review backing the trades.";
        }
        if (currentWeek.manualReviewGapTrades > 0) {
            return "Moderate confidence. The coaching is directionally useful, but some trades were under-documented.";
        }
        return "Good confidence. The week has enough review evidence to judge behavior, not just outcome.";
    }

    private boolean containsMistake(WeekSnapshot currentWeek, String label) {
        return currentWeek.mistakeCounts.containsKey(label);
    }

    private WeekSnapshot buildWeekSnapshot(LocalDate startDate, LocalDate endDate, List<Trade> trades) {
        List<Trade> sortedTrades = new ArrayList<>(trades);
        sortedTrades.sort(Comparator
                .comparing(this::resolveTradeTimestamp, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(Trade::getId, Comparator.nullsLast(String::compareTo)));

        Map<String, TradeReview> reviewMap = buildReviewMap(sortedTrades);
        Map<String, List<String>> mistakeMap = buildMistakeMap(sortedTrades);

        WeekAccumulator acc = new WeekAccumulator();
        Map<String, Integer> symbolCounts = new HashMap<>();
        Map<String, Integer> setupCounts = new HashMap<>();

        for (Trade trade : sortedTrades) {
            LocalDateTime timestamp = resolveTradeTimestamp(trade);
            if (timestamp != null) {
                acc.tradeDays.add(timestamp.toLocalDate());
            }

            acc.totalTrades++;
            acc.totalPnl += trade.getPnl();

            if (isWin(trade)) {
                acc.winTrades++;
            } else if (isLoss(trade)) {
                acc.lossTrades++;
            } else {
                acc.breakevenTrades++;
            }

            if (trade.hasKnownRMultiple()) {
                acc.knownRTrades++;
                acc.totalR += trade.getRMultiple();
                if (isWin(trade) && trade.getRMultiple() < 1.0) {
                    acc.lowReturnWins++;
                }
            }

            String symbol = normalizeLabel(trade.getSymbol());
            String session = normalizeLabel(trade.getSession());
            String setup = normalizeLabel(trade.getSetupName());
            acc.symbols.add(symbol);
            acc.sessions.add(session);
            increment(symbolCounts, symbol);
            increment(setupCounts, setup);

            TradeReview review = reviewMap.get(trade.getId());
            if (review != null) {
                acc.reviewedTrades++;
                Integer processScore = resolveProcessScore(review);
                if (processScore != null) {
                    acc.totalProcessScore += processScore;
                    acc.processScoreCount++;
                }
                if (hasStructuredManualReview(review)) {
                    acc.manualReviewedTrades++;
                }
                collectReviewMistakes(review, acc.mistakeCounts);
            }

            for (String mistakeName : mistakeMap.getOrDefault(trade.getId(), List.of())) {
                increment(acc.mistakeCounts, mistakeName);
            }
        }

        acc.manualReviewGapTrades = Math.max(0, acc.totalTrades - acc.manualReviewedTrades);
        Double avgProcessScore = acc.processScoreCount == 0
                ? null
                : round1(acc.totalProcessScore / acc.processScoreCount);
        Double avgR = acc.knownRTrades == 0 ? null : round2(acc.totalR / acc.knownRTrades);

        List<Map.Entry<String, Integer>> sortedMistakes = acc.mistakeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(5)
                .toList();
        LinkedHashMap<String, Integer> topMistakes = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : sortedMistakes) {
            topMistakes.put(entry.getKey(), entry.getValue());
        }

        return new WeekSnapshot(
                startDate,
                endDate,
                acc.totalTrades,
                acc.winTrades,
                acc.lossTrades,
                acc.breakevenTrades,
                round2(acc.totalPnl),
                avgR,
                avgProcessScore,
                acc.reviewedTrades,
                acc.manualReviewedTrades,
                acc.manualReviewGapTrades,
                acc.lowReturnWins,
                acc.symbols.size(),
                acc.sessions.size(),
                acc.tradeDays.size(),
                resolveTopLabel(symbolCounts),
                resolveTopLabel(setupCounts),
                topMistakes,
                acc.lossTrades == 0 && acc.winTrades > 0
        );
    }

    private Map<String, TradeReview> buildReviewMap(List<Trade> trades) {
        List<String> tradeIds = trades.stream()
                .map(Trade::getId)
                .filter(StringUtils::hasText)
                .toList();
        if (tradeIds.isEmpty()) {
            return Map.of();
        }

        Map<String, TradeReview> reviewMap = new HashMap<>();
        for (TradeReview review : tradeReviewRepository.findByTradeIdIn(tradeIds)) {
            if (review.getTrade() != null && StringUtils.hasText(review.getTrade().getId())) {
                reviewMap.put(review.getTrade().getId(), review);
            }
        }
        return reviewMap;
    }

    private Map<String, List<String>> buildMistakeMap(List<Trade> trades) {
        List<String> tradeIds = trades.stream()
                .map(Trade::getId)
                .filter(StringUtils::hasText)
                .toList();
        if (tradeIds.isEmpty()) {
            return Map.of();
        }

        Map<String, LinkedHashSet<String>> mistakesByTrade = new HashMap<>();
        for (TradeMistakeTag link : tradeMistakeTagRepository.findByTradeIdIn(tradeIds)) {
            if (link.getTrade() == null || !StringUtils.hasText(link.getTrade().getId())) {
                continue;
            }
            if (link.getMistakeTag() == null || !StringUtils.hasText(link.getMistakeTag().getName())) {
                continue;
            }
            mistakesByTrade.computeIfAbsent(link.getTrade().getId(), ignored -> new LinkedHashSet<>())
                    .add(normalizeLabel(link.getMistakeTag().getName()));
        }

        Map<String, List<String>> result = new HashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : mistakesByTrade.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return result;
    }

    private List<Trade> filterTradesByRange(List<Trade> trades, LocalDateTime from, LocalDateTime to) {
        List<Trade> filtered = new ArrayList<>();
        for (Trade trade : trades) {
            LocalDateTime timestamp = resolveTradeTimestamp(trade);
            if (timestamp == null) {
                continue;
            }
            if (from != null && timestamp.isBefore(from)) {
                continue;
            }
            if (to != null && timestamp.isAfter(to)) {
                continue;
            }
            filtered.add(trade);
        }
        return filtered;
    }

    private LocalDateTime resolveTradeTimestamp(Trade trade) {
        if (trade.getEntryTime() != null) {
            return trade.getEntryTime();
        }
        if (trade.getTradeDate() != null) {
            return trade.getTradeDate();
        }
        return trade.getCreatedAt();
    }

    private Integer resolveProcessScore(TradeReview review) {
        if (review.getAiProcessScore() != null) {
            return review.getAiProcessScore();
        }
        return review.getQualityScore();
    }

    private boolean hasStructuredManualReview(TradeReview review) {
        int answeredFields = countAnsweredManualFields(review);
        if (answeredFields >= 5) {
            return true;
        }
        return hasAnyText(
                review.getPreTradeChecklist(),
                review.getPostTradeReview(),
                review.getLessonLearned(),
                review.getImprovementNote()
        );
    }

    private int countAnsweredManualFields(TradeReview review) {
        int answered = 0;
        answered += countNonNullBooleans(
                review.getFollowedPlan(),
                review.getHadConfirmation(),
                review.getRespectedRisk(),
                review.getAlignedHtfBias(),
                review.getCorrectSession(),
                review.getCorrectSetup(),
                review.getCorrectPoi(),
                review.getHadFomo(),
                review.getEnteredBeforeNews(),
                review.getWouldTakeAgain()
        );
        if (review.getEntryTimingRating() != null) {
            answered++;
        }
        if (review.getExitQualityRating() != null) {
            answered++;
        }
        return answered;
    }

    private int countNonNullBooleans(Boolean... values) {
        int count = 0;
        for (Boolean value : values) {
            if (value != null) {
                count++;
            }
        }
        return count;
    }

    private boolean hasAnyText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return true;
            }
        }
        return false;
    }

    private void collectReviewMistakes(TradeReview review, Map<String, Integer> counts) {
        if (Boolean.FALSE.equals(review.getFollowedPlan())) {
            increment(counts, "Did not follow plan");
        }
        if (Boolean.FALSE.equals(review.getHadConfirmation())) {
            increment(counts, "Entered without confirmation");
        }
        if (Boolean.FALSE.equals(review.getRespectedRisk())) {
            increment(counts, "Risk not respected");
        }
        if (Boolean.FALSE.equals(review.getAlignedHtfBias())) {
            increment(counts, "Against higher-timeframe bias");
        }
        if (Boolean.FALSE.equals(review.getCorrectSession())) {
            increment(counts, "Wrong session");
        }
        if (Boolean.FALSE.equals(review.getCorrectSetup())) {
            increment(counts, "Wrong setup");
        }
        if (Boolean.FALSE.equals(review.getCorrectPoi())) {
            increment(counts, "Poor point of interest");
        }
        if (Boolean.TRUE.equals(review.getHadFomo())) {
            increment(counts, "FOMO entry");
        }
        if (Boolean.TRUE.equals(review.getEnteredBeforeNews())) {
            increment(counts, "Entered before news");
        }
    }

    private void increment(Map<String, Integer> counts, String label) {
        if (!StringUtils.hasText(label)) {
            return;
        }
        counts.put(label, counts.getOrDefault(label, 0) + 1);
    }

    private String resolveTopLabel(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse("N/A");
    }

    private String formatRange(LocalDate startDate, LocalDate endDate) {
        return DATE_LABEL_FORMATTER.format(startDate) + " - " + DATE_LABEL_FORMATTER.format(endDate);
    }

    private String normalizeLabel(String value) {
        if (!StringUtils.hasText(value)) {
            return "N/A";
        }
        return value.trim();
    }

    private boolean isWin(Trade trade) {
        return trade != null && "WIN".equalsIgnoreCase(normalizeLabel(trade.getResult()));
    }

    private boolean isLoss(Trade trade) {
        return trade != null && "LOSS".equalsIgnoreCase(normalizeLabel(trade.getResult()));
    }

    private String pluralize(int count) {
        return count == 1 ? "" : "s";
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static final class WeekAccumulator {
        private int totalTrades;
        private int winTrades;
        private int lossTrades;
        private int breakevenTrades;
        private int knownRTrades;
        private int reviewedTrades;
        private int manualReviewedTrades;
        private int manualReviewGapTrades;
        private int lowReturnWins;
        private double totalPnl;
        private double totalR;
        private double totalProcessScore;
        private int processScoreCount;
        private final Set<String> symbols = new LinkedHashSet<>();
        private final Set<String> sessions = new LinkedHashSet<>();
        private final Set<LocalDate> tradeDays = new LinkedHashSet<>();
        private final Map<String, Integer> mistakeCounts = new HashMap<>();
    }

    private static final class WeekSnapshot {
        private final LocalDate startDate;
        private final LocalDate endDate;
        private final int totalTrades;
        private final int winTrades;
        private final int lossTrades;
        private final int breakevenTrades;
        private final double totalPnl;
        private final Double avgR;
        private final Double avgProcessScore;
        private final int reviewedTrades;
        private final int manualReviewedTrades;
        private final int manualReviewGapTrades;
        private final int lowReturnWins;
        private final int symbolCount;
        private final int sessionCount;
        private final int tradeDayCount;
        private final String primarySymbol;
        private final String primarySetup;
        private final LinkedHashMap<String, Integer> mistakeCounts;
        private final boolean allWins;

        private WeekSnapshot(
                LocalDate startDate,
                LocalDate endDate,
                int totalTrades,
                int winTrades,
                int lossTrades,
                int breakevenTrades,
                double totalPnl,
                Double avgR,
                Double avgProcessScore,
                int reviewedTrades,
                int manualReviewedTrades,
                int manualReviewGapTrades,
                int lowReturnWins,
                int symbolCount,
                int sessionCount,
                int tradeDayCount,
                String primarySymbol,
                String primarySetup,
                LinkedHashMap<String, Integer> mistakeCounts,
                boolean allWins
        ) {
            this.startDate = startDate;
            this.endDate = endDate;
            this.totalTrades = totalTrades;
            this.winTrades = winTrades;
            this.lossTrades = lossTrades;
            this.breakevenTrades = breakevenTrades;
            this.totalPnl = totalPnl;
            this.avgR = avgR;
            this.avgProcessScore = avgProcessScore;
            this.reviewedTrades = reviewedTrades;
            this.manualReviewedTrades = manualReviewedTrades;
            this.manualReviewGapTrades = manualReviewGapTrades;
            this.lowReturnWins = lowReturnWins;
            this.symbolCount = symbolCount;
            this.sessionCount = sessionCount;
            this.tradeDayCount = tradeDayCount;
            this.primarySymbol = primarySymbol;
            this.primarySetup = primarySetup;
            this.mistakeCounts = mistakeCounts;
            this.allWins = allWins;
        }
    }

    public static final class WeeklyCoachReport {
        private final LocalDate startDate;
        private final LocalDate endDate;
        private final int totalTrades;
        private final Integer avgProcessScore;
        private final int reviewedTrades;
        private final int manualReviewedTrades;
        private final int symbolCount;
        private final int sessionCount;
        private final boolean empty;
        private final String summary;
        private final List<String> keyFindings;
        private final List<CoachIssue> topMistakes;
        private final List<String> improvements;
        private final List<String> nextWeekPlan;
        private final String confidenceNote;

        public WeeklyCoachReport(
                LocalDate startDate,
                LocalDate endDate,
                int totalTrades,
                Integer avgProcessScore,
                int reviewedTrades,
                int manualReviewedTrades,
                int symbolCount,
                int sessionCount,
                boolean empty,
                String summary,
                List<String> keyFindings,
                List<CoachIssue> topMistakes,
                List<String> improvements,
                List<String> nextWeekPlan,
                String confidenceNote
        ) {
            this.startDate = startDate;
            this.endDate = endDate;
            this.totalTrades = totalTrades;
            this.avgProcessScore = avgProcessScore;
            this.reviewedTrades = reviewedTrades;
            this.manualReviewedTrades = manualReviewedTrades;
            this.symbolCount = symbolCount;
            this.sessionCount = sessionCount;
            this.empty = empty;
            this.summary = summary;
            this.keyFindings = List.copyOf(keyFindings);
            this.topMistakes = List.copyOf(topMistakes);
            this.improvements = List.copyOf(improvements);
            this.nextWeekPlan = List.copyOf(nextWeekPlan);
            this.confidenceNote = confidenceNote;
        }

        public LocalDate getStartDate() {
            return startDate;
        }

        public LocalDate getEndDate() {
            return endDate;
        }

        public int getTotalTrades() {
            return totalTrades;
        }

        public Integer getAvgProcessScore() {
            return avgProcessScore;
        }

        public int getReviewedTrades() {
            return reviewedTrades;
        }

        public int getManualReviewedTrades() {
            return manualReviewedTrades;
        }

        public int getSymbolCount() {
            return symbolCount;
        }

        public int getSessionCount() {
            return sessionCount;
        }

        public boolean isEmpty() {
            return empty;
        }

        public String getSummary() {
            return summary;
        }

        public List<String> getKeyFindings() {
            return keyFindings;
        }

        public List<CoachIssue> getTopMistakes() {
            return topMistakes;
        }

        public List<String> getImprovements() {
            return improvements;
        }

        public List<String> getNextWeekPlan() {
            return nextWeekPlan;
        }

        public String getConfidenceNote() {
            return confidenceNote;
        }

        public String getDateRangeLabel() {
            return DATE_LABEL_FORMATTER.format(startDate) + " - " + DATE_LABEL_FORMATTER.format(endDate);
        }

        public int getReviewGapTrades() {
            return Math.max(0, totalTrades - manualReviewedTrades);
        }

        public CoachIssue getPrimaryIssue() {
            return topMistakes.isEmpty() ? null : topMistakes.getFirst();
        }

        public List<String> getDashboardImprovements() {
            return improvements.stream()
                    .limit(3)
                    .toList();
        }

        public String getDashboardSummary() {
            String executionLabel;
            if (avgProcessScore == null) {
                executionLabel = "Execution quality is hard to trust";
            } else if (avgProcessScore >= 85) {
                executionLabel = "Execution was strong";
            } else if (avgProcessScore >= 70) {
                executionLabel = "Execution was decent";
            } else {
                executionLabel = "Execution was weak";
            }

            String consistencyLabel;
            if (symbolCount <= 1 && reviewGapTrades() == 0) {
                consistencyLabel = "and consistent";
            } else if (symbolCount <= 1) {
                consistencyLabel = "but review discipline was inconsistent";
            } else {
                consistencyLabel = "but consistency is still loose";
            }

            CoachIssue primaryIssue = getPrimaryIssue();
            String issueLabel = primaryIssue != null && StringUtils.hasText(primaryIssue.getTitle())
                    ? primaryIssue.getTitle()
                    : "process discipline";

            return executionLabel + " " + consistencyLabel + ". " + issueLabel + " remains the main issue.";
        }

        private int reviewGapTrades() {
            return Math.max(0, totalTrades - manualReviewedTrades);
        }
    }

    public static final class CoachIssue {
        private final int rank;
        private final String title;
        private final String detail;

        public CoachIssue(int rank, String title, String detail) {
            this.rank = rank;
            this.title = title;
            this.detail = detail;
        }

        public int getRank() {
            return rank;
        }

        public String getTitle() {
            return title;
        }

        public String getDetail() {
            return detail;
        }
    }
}
