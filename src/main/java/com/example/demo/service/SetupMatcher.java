package com.example.demo.service;

import com.example.demo.entity.Setup;
import com.example.demo.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class SetupMatcher {

    private final SetupService setupService;

    public SetupMatcher(SetupService setupService) {
        this.setupService = setupService;
    }

    public TradingViewChartImportService.TradeChartAnalysis resolve(
            TradingViewChartImportService.TradeChartAnalysis analysis,
            User user
    ) {
        if (analysis == null || user == null) {
            return analysis;
        }

        List<Setup> setups = setupService.findActiveByUser(user.getId());
        if (setups == null || setups.isEmpty()) {
            return analysis.withSetupResolution(
                    null,
                    null,
                    null,
                    buildSuggestedName(analysis),
                    buildSuggestedDescription(analysis)
            );
        }

        String aiSetupGuess = normalizeText(analysis.setupGuess());
        String aiCorpus = String.join(" | ", nonBlank(
                analysis.setupGuess(),
                analysis.htfStructure(),
                analysis.ltfTrigger(),
                analysis.tradeIdea()
        ));
        Set<String> aiKeywords = extractKeywords(aiCorpus);

        Setup bestSetup = null;
        int bestScore = 0;
        boolean exactNameMatch = false;

        for (Setup setup : setups) {
            if (setup == null || !StringUtils.hasText(setup.getName())) {
                continue;
            }

            String setupName = normalizeText(setup.getName());
            String setupCorpus = String.join(" | ", nonBlank(setup.getName(), setup.getDescription()));
            Set<String> setupKeywords = extractKeywords(setupCorpus);

            int score = 0;
            boolean exactMatch = false;
            if (aiSetupGuess != null && aiSetupGuess.equals(setupName)) {
                score += 10;
                exactMatch = true;
            } else if (aiSetupGuess != null && (aiSetupGuess.contains(setupName) || setupName.contains(aiSetupGuess))) {
                score += 6;
            }

            for (String keyword : aiKeywords) {
                if (setupKeywords.contains(keyword)) {
                    score += 2;
                }
            }

            if (analysis.htfStructure() != null && containsNormalized(setupCorpus, analysis.htfStructure())) {
                score += 1;
            }
            if (analysis.ltfTrigger() != null && containsNormalized(setupCorpus, analysis.ltfTrigger())) {
                score += 1;
            }

            if (score > bestScore) {
                bestScore = score;
                bestSetup = setup;
                exactNameMatch = exactMatch;
            }
        }

        if (bestSetup != null && bestScore >= 5) {
            return analysis.withSetupResolution(
                    bestSetup.getId(),
                    bestSetup.getName(),
                    resolveConfidence(bestScore, exactNameMatch),
                    null,
                    null
            );
        }

        return analysis.withSetupResolution(
                null,
                null,
                null,
                buildSuggestedName(analysis),
                buildSuggestedDescription(analysis)
        );
    }

    private String resolveConfidence(int score, boolean exactNameMatch) {
        if (exactNameMatch || score >= 10) {
            return "high";
        }
        if (score >= 7) {
            return "medium";
        }
        return "low";
    }

    private boolean containsNormalized(String haystack, String needle) {
        String normalizedHaystack = normalizeText(haystack);
        String normalizedNeedle = normalizeText(needle);
        return normalizedHaystack != null && normalizedNeedle != null && normalizedHaystack.contains(normalizedNeedle);
    }

    private String buildSuggestedName(TradingViewChartImportService.TradeChartAnalysis analysis) {
        if (StringUtils.hasText(analysis.setupGuess())) {
            return titleize(analysis.setupGuess());
        }

        List<String> keywords = new ArrayList<>(extractKeywords(String.join(" | ", nonBlank(
                analysis.htfStructure(),
                analysis.ltfTrigger()
        ))));
        if (keywords.isEmpty()) {
            return null;
        }

        return titleize(String.join(" ", keywords.stream().limit(4).toList()));
    }

    private String buildSuggestedDescription(TradingViewChartImportService.TradeChartAnalysis analysis) {
        List<String> fragments = new ArrayList<>();
        if (StringUtils.hasText(analysis.htfBias())) {
            fragments.add("HTF bias: " + analysis.htfBias().trim());
        }
        if (StringUtils.hasText(analysis.htfStructure())) {
            fragments.add("HTF structure: " + analysis.htfStructure().trim());
        }
        if (StringUtils.hasText(analysis.ltfTrigger())) {
            fragments.add("LTF trigger: " + analysis.ltfTrigger().trim());
        }
        if (StringUtils.hasText(analysis.tradeIdea())) {
            fragments.add("Trade idea: " + analysis.tradeIdea().trim());
        }
        if (fragments.isEmpty()) {
            return null;
        }
        return String.join(" | ", fragments);
    }

    private Set<String> extractKeywords(String value) {
        Set<String> keywords = new LinkedHashSet<>();
        String normalized = normalizeText(value);
        if (normalized == null) {
            return keywords;
        }

        addPhraseKeyword(normalized, keywords, "order block", "ob");
        addPhraseKeyword(normalized, keywords, "fair value gap", "fvg");
        addPhraseKeyword(normalized, keywords, "market structure shift", "mss");
        addPhraseKeyword(normalized, keywords, "break of structure", "bos");
        addPhraseKeyword(normalized, keywords, "liquidity sweep", "liquidity_sweep");
        addPhraseKeyword(normalized, keywords, "liquidity grab", "liquidity_sweep");
        addPhraseKeyword(normalized, keywords, "ob", "ob");
        addPhraseKeyword(normalized, keywords, "fvg", "fvg");
        addPhraseKeyword(normalized, keywords, "mss", "mss");
        addPhraseKeyword(normalized, keywords, "bos", "bos");
        addPhraseKeyword(normalized, keywords, "premium", "premium");
        addPhraseKeyword(normalized, keywords, "discount", "discount");
        addPhraseKeyword(normalized, keywords, "reversal", "reversal");
        addPhraseKeyword(normalized, keywords, "continuation", "continuation");
        addPhraseKeyword(normalized, keywords, "breaker", "breaker");
        addPhraseKeyword(normalized, keywords, "mitigation", "mitigation");

        for (String token : normalized.split("[^a-z0-9]+")) {
            if (token.length() < 3) {
                continue;
            }
            if (Set.of("the", "and", "with", "from", "then", "into", "that", "this", "zone", "chart", "trade", "setup").contains(token)) {
                continue;
            }
            keywords.add(token);
        }

        return keywords;
    }

    private void addPhraseKeyword(String normalized, Set<String> keywords, String phrase, String keyword) {
        if (normalized.contains(phrase)) {
            keywords.add(keyword);
        }
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        return value.toLowerCase(Locale.ROOT).trim()
                .replace("orderblock", "order block")
                .replace("fairvaluegap", "fair value gap");
    }

    private List<String> nonBlank(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private String titleize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String[] words = value.trim().replace('_', ' ').split("\\s+");
        List<String> titled = new ArrayList<>();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            String upper = word.toUpperCase(Locale.ROOT);
            if (Set.of("OB", "FVG", "MSS", "BOS").contains(upper)) {
                titled.add(upper);
                continue;
            }
            titled.add(Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase(Locale.ROOT));
        }
        return String.join(" ", titled);
    }
}
