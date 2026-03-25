package com.tradejournal.trade.service;

import com.tradejournal.trade.domain.Trade;
import com.tradejournal.trade.domain.TradeReview;
import com.tradejournal.trade.repository.TradeReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeReviewService {

    private final TradeReviewRepository tradeReviewRepository;

    public TradeReviewService(TradeReviewRepository tradeReviewRepository) {
        this.tradeReviewRepository = tradeReviewRepository;
    }

    public TradeReview getOrInitByTrade(Trade trade) {
        return tradeReviewRepository.findByTradeId(trade.getId())
                .orElseGet(() -> {
                    TradeReview review = new TradeReview();
                    review.setTrade(trade);
                    return review;
                });
    }

    public TradeReview findByTradeId(String tradeId) {
        return tradeReviewRepository.findByTradeId(tradeId).orElse(null);
    }

    @Transactional
    public TradeReview upsertForTrade(Trade trade, TradeReview form) {
        TradeReview review = tradeReviewRepository.findByTradeId(trade.getId()).orElseGet(TradeReview::new);
        review.setTrade(trade);

        review.setFollowedPlan(form.getFollowedPlan());
        review.setHadConfirmation(form.getHadConfirmation());
        review.setRespectedRisk(form.getRespectedRisk());
        review.setAlignedHtfBias(form.getAlignedHtfBias());
        review.setCorrectSession(form.getCorrectSession());
        review.setCorrectSetup(form.getCorrectSetup());
        review.setCorrectPoi(form.getCorrectPoi());
        review.setHadFomo(form.getHadFomo());
        review.setEnteredBeforeNews(form.getEnteredBeforeNews());
        review.setEntryTimingRating(clampRating(form.getEntryTimingRating()));
        review.setExitQualityRating(clampRating(form.getExitQualityRating()));
        review.setWouldTakeAgain(form.getWouldTakeAgain());
        review.setPreTradeChecklist(trimToNull(form.getPreTradeChecklist()));
        review.setPostTradeReview(trimToNull(form.getPostTradeReview()));
        review.setLessonLearned(trimToNull(form.getLessonLearned()));
        review.setImprovementNote(trimToNull(form.getImprovementNote()));

        review.setQualityScore(calculateQualityScore(review));
        return tradeReviewRepository.save(review);
    }

    public int calculateQualityScore(TradeReview review) {
        int score = 0;

        score += yesNoScore(review.getAlignedHtfBias(), true);
        score += yesNoScore(review.getCorrectSession(), true);
        score += yesNoScore(review.getCorrectSetup(), true);
        score += yesNoScore(review.getCorrectPoi(), true);
        score += yesNoScore(review.getHadConfirmation(), true);
        score += yesNoScore(review.getRespectedRisk(), true);
        score += yesNoScore(review.getHadFomo(), false);
        score += yesNoScore(review.getEnteredBeforeNews(), false);

        score += ratingScore(review.getEntryTimingRating());
        score += ratingScore(review.getExitQualityRating());

        return Math.max(0, Math.min(100, score));
    }

    public String resolveScoreGrade(Integer qualityScore) {
        if (qualityScore == null) {
            return "N/A";
        }
        if (qualityScore >= 85) {
            return "A";
        }
        if (qualityScore >= 70) {
            return "B";
        }
        return "C";
    }

    public String resolveScoreLabel(Integer qualityScore) {
        if (qualityScore == null) {
            return "No review yet";
        }
        if (qualityScore >= 85) {
            return "High quality";
        }
        if (qualityScore >= 70) {
            return "Medium quality";
        }
        return "Low quality";
    }

    public Integer resolveEffectiveQualityScore(TradeReview review) {
        if (review == null) {
            return null;
        }
        if (review.getQualityScore() != null) {
            return review.getQualityScore();
        }
        return review.getAiProcessScore();
    }

    public String resolveEffectiveQualitySourceLabel(TradeReview review) {
        if (review == null) {
            return null;
        }
        if (review.getQualityScore() != null) {
            return "Self Review";
        }
        if (review.getAiProcessScore() != null) {
            return "AI Review";
        }
        return null;
    }

    public String resolveProcessOutcomeLabel(Trade trade, TradeReview review) {
        Integer qualityScore = resolveEffectiveQualityScore(review);
        if (qualityScore == null || trade == null) {
            return "Not enough review data";
        }

        boolean goodProcess = qualityScore >= 70;
        boolean goodOutcome = trade.getPnl() > 0;

        if (goodProcess && goodOutcome) {
            return "Good process, good outcome";
        }
        if (goodProcess) {
            return "Good process, bad outcome";
        }
        if (goodOutcome) {
            return "Bad process, good outcome";
        }
        return "Bad process, bad outcome";
    }

    public void deleteByTradeId(String tradeId) {
        tradeReviewRepository.deleteByTradeId(tradeId);
    }

    private int yesNoScore(Boolean value, boolean rewardedWhenTrue) {
        if (value == null) {
            return 0;
        }
        if (rewardedWhenTrue) {
            return value ? 10 : 0;
        }
        return value ? 0 : 10;
    }

    private int ratingScore(Integer rating) {
        if (rating == null) {
            return 0;
        }
        int safe = clampRating(rating);
        return safe * 2;
    }

    private Integer clampRating(Integer rating) {
        if (rating == null) {
            return null;
        }
        return Math.max(0, Math.min(5, rating));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
