package com.tradejournal.trade.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "trade_reviews",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_trade_review_trade", columnNames = {"trade_id"})
        }
)
public class TradeReview {

    @Id
    @Column(length = 36)
    private String id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trade_id", nullable = false)
    private Trade trade;

    private Integer qualityScore;

    private Boolean followedPlan;
    private Boolean hadConfirmation;
    private Boolean respectedRisk;

    private Boolean alignedHtfBias;
    private Boolean correctSession;
    private Boolean correctSetup;
    private Boolean correctPoi;
    private Boolean hadFomo;
    private Boolean enteredBeforeNews;

    @Min(0)
    @Max(5)
    private Integer entryTimingRating;

    @Min(0)
    @Max(5)
    private Integer exitQualityRating;

    private Boolean wouldTakeAgain;

    @Column(columnDefinition = "TEXT")
    private String preTradeChecklist;

    @Column(columnDefinition = "TEXT")
    private String postTradeReview;

    @Column(columnDefinition = "TEXT")
    private String lessonLearned;

    @Column(columnDefinition = "TEXT")
    private String improvementNote;

    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @Column(name = "ai_strengths_json", columnDefinition = "TEXT")
    private String aiStrengthsJson;

    @Column(name = "ai_weaknesses_json", columnDefinition = "TEXT")
    private String aiWeaknessesJson;

    @Column(name = "ai_improvements_json", columnDefinition = "TEXT")
    private String aiImprovementsJson;

    @Column(name = "ai_suggested_mistake_tags_json", columnDefinition = "TEXT")
    private String aiSuggestedMistakeTagsJson;

    @Column(name = "ai_confidence", length = 16)
    private String aiConfidence;

    @Column(name = "ai_model", length = 80)
    private String aiModel;

    @Column(name = "ai_process_score")
    private Integer aiProcessScore;

    @Column(name = "ai_generated_at")
    private LocalDateTime aiGeneratedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Trade getTrade() {
        return trade;
    }

    public void setTrade(Trade trade) {
        this.trade = trade;
    }

    public Integer getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(Integer qualityScore) {
        this.qualityScore = qualityScore;
    }

    public Boolean getFollowedPlan() {
        return followedPlan;
    }

    public void setFollowedPlan(Boolean followedPlan) {
        this.followedPlan = followedPlan;
    }

    public Boolean getHadConfirmation() {
        return hadConfirmation;
    }

    public void setHadConfirmation(Boolean hadConfirmation) {
        this.hadConfirmation = hadConfirmation;
    }

    public Boolean getRespectedRisk() {
        return respectedRisk;
    }

    public void setRespectedRisk(Boolean respectedRisk) {
        this.respectedRisk = respectedRisk;
    }

    public Boolean getAlignedHtfBias() {
        return alignedHtfBias;
    }

    public void setAlignedHtfBias(Boolean alignedHtfBias) {
        this.alignedHtfBias = alignedHtfBias;
    }

    public Boolean getCorrectSession() {
        return correctSession;
    }

    public void setCorrectSession(Boolean correctSession) {
        this.correctSession = correctSession;
    }

    public Boolean getCorrectSetup() {
        return correctSetup;
    }

    public void setCorrectSetup(Boolean correctSetup) {
        this.correctSetup = correctSetup;
    }

    public Boolean getCorrectPoi() {
        return correctPoi;
    }

    public void setCorrectPoi(Boolean correctPoi) {
        this.correctPoi = correctPoi;
    }

    public Boolean getHadFomo() {
        return hadFomo;
    }

    public void setHadFomo(Boolean hadFomo) {
        this.hadFomo = hadFomo;
    }

    public Boolean getEnteredBeforeNews() {
        return enteredBeforeNews;
    }

    public void setEnteredBeforeNews(Boolean enteredBeforeNews) {
        this.enteredBeforeNews = enteredBeforeNews;
    }

    public Integer getEntryTimingRating() {
        return entryTimingRating;
    }

    public void setEntryTimingRating(Integer entryTimingRating) {
        this.entryTimingRating = entryTimingRating;
    }

    public Integer getExitQualityRating() {
        return exitQualityRating;
    }

    public void setExitQualityRating(Integer exitQualityRating) {
        this.exitQualityRating = exitQualityRating;
    }

    public Boolean getWouldTakeAgain() {
        return wouldTakeAgain;
    }

    public void setWouldTakeAgain(Boolean wouldTakeAgain) {
        this.wouldTakeAgain = wouldTakeAgain;
    }

    public String getPreTradeChecklist() {
        return preTradeChecklist;
    }

    public void setPreTradeChecklist(String preTradeChecklist) {
        this.preTradeChecklist = preTradeChecklist;
    }

    public String getPostTradeReview() {
        return postTradeReview;
    }

    public void setPostTradeReview(String postTradeReview) {
        this.postTradeReview = postTradeReview;
    }

    public String getLessonLearned() {
        return lessonLearned;
    }

    public void setLessonLearned(String lessonLearned) {
        this.lessonLearned = lessonLearned;
    }

    public String getImprovementNote() {
        return improvementNote;
    }

    public void setImprovementNote(String improvementNote) {
        this.improvementNote = improvementNote;
    }

    public String getAiSummary() {
        return aiSummary;
    }

    public void setAiSummary(String aiSummary) {
        this.aiSummary = aiSummary;
    }

    public String getAiStrengthsJson() {
        return aiStrengthsJson;
    }

    public void setAiStrengthsJson(String aiStrengthsJson) {
        this.aiStrengthsJson = aiStrengthsJson;
    }

    public String getAiWeaknessesJson() {
        return aiWeaknessesJson;
    }

    public void setAiWeaknessesJson(String aiWeaknessesJson) {
        this.aiWeaknessesJson = aiWeaknessesJson;
    }

    public String getAiImprovementsJson() {
        return aiImprovementsJson;
    }

    public void setAiImprovementsJson(String aiImprovementsJson) {
        this.aiImprovementsJson = aiImprovementsJson;
    }

    public String getAiSuggestedMistakeTagsJson() {
        return aiSuggestedMistakeTagsJson;
    }

    public void setAiSuggestedMistakeTagsJson(String aiSuggestedMistakeTagsJson) {
        this.aiSuggestedMistakeTagsJson = aiSuggestedMistakeTagsJson;
    }

    public String getAiConfidence() {
        return aiConfidence;
    }

    public void setAiConfidence(String aiConfidence) {
        this.aiConfidence = aiConfidence;
    }

    public String getAiModel() {
        return aiModel;
    }

    public void setAiModel(String aiModel) {
        this.aiModel = aiModel;
    }

    public Integer getAiProcessScore() {
        return aiProcessScore;
    }

    public void setAiProcessScore(Integer aiProcessScore) {
        this.aiProcessScore = aiProcessScore;
    }

    public LocalDateTime getAiGeneratedAt() {
        return aiGeneratedAt;
    }

    public void setAiGeneratedAt(LocalDateTime aiGeneratedAt) {
        this.aiGeneratedAt = aiGeneratedAt;
    }

    @Transient
    public boolean hasAiReview() {
        return aiGeneratedAt != null
                || (aiSummary != null && !aiSummary.isBlank())
                || (aiStrengthsJson != null && !aiStrengthsJson.isBlank())
                || (aiWeaknessesJson != null && !aiWeaknessesJson.isBlank())
                || (aiImprovementsJson != null && !aiImprovementsJson.isBlank())
                || (aiSuggestedMistakeTagsJson != null && !aiSuggestedMistakeTagsJson.isBlank());
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
