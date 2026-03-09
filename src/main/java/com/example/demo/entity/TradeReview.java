package com.example.demo.entity;

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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
