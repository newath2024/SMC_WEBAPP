package com.example.demo.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "trades")
public class Trade {

    @Id
    @Column(length = 36)
    private String id;

    private LocalDateTime tradeDate;

    @Column(name = "entry_time", nullable = true)
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime entryTime;

    @Column(name = "exit_time", nullable = true)
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime exitTime;

    @NotBlank
    @Column(name = "account_label", nullable = false)
    private String accountLabel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "setup_id")
    private Setup setup;

    @NotBlank
    private String symbol;

    @NotBlank
    private String direction; // BUY / SELL

    @NotBlank
    private String htf;

    @NotBlank
    private String ltf;

    @NotNull
    @Positive
    private double entryPrice;

    @NotNull
    @Positive
    private double stopLoss;

    @Column(name = "initial_stop_loss")
    @Positive
    private Double initialStopLoss;

    @Column(name = "initial_stop_loss_confirmed")
    private Boolean initialStopLossConfirmed;

    @Positive
    private double takeProfit;

    private double exitPrice;

    @Positive
    private double positionSize;

    @Column(name = "mt5_position_id", length = 64)
    private String mt5PositionId;

    private String result; // WIN / LOSS / BE and take partial

    private double pnl;

    @Column(name = "r_multiple")
    private double rMultiple;

    @Column(name = "r_multiple_source", length = 32)
    private String rMultipleSource;

    private String session; // ASIA / LONDON / NEW_YORK / OTHER

    @Column(name = "estimated_holding_minutes")
    private Integer estimatedHoldingMinutes;

    @Column(name = "estimated_ltf_candles_held")
    private Integer estimatedLtfCandlesHeld;

    @Column(name = "session_guess", length = 32)
    private String sessionGuess;

    @Column(name = "session_confidence", length = 16)
    private String sessionConfidence;

    @Column(columnDefinition = "TEXT")
    private String note;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Transient
    private List<MistakeTag> mistakes = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (tradeDate == null) {
            tradeDate = entryTime != null ? entryTime : createdAt;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
        if (tradeDate == null) {
            tradeDate = entryTime != null ? entryTime : createdAt;
        }
    }

    @Transient
    public Long getExactHoldingMinutes() {
        if (entryTime == null || exitTime == null) {
            return null;
        }
        return Duration.between(entryTime, exitTime).toMinutes();
    }

    @Transient
    public Long getHoldingMinutes() {
        Long exactHolding = getExactHoldingMinutes();
        if (exactHolding != null) {
            return exactHolding;
        }
        return estimatedHoldingMinutes == null ? null : estimatedHoldingMinutes.longValue();
    }

    @Transient
    public Integer getLtfMinutes() {
        if (ltf == null) return null;

        return switch (ltf) {
            case "M1" -> 1;
            case "M5" -> 5;
            case "M15" -> 15;
            case "M30" -> 30;
            case "H1" -> 60;
            case "H4" -> 240;
            default -> null;
        };
    }

    @Transient
    public BigDecimal getEquivalentCandles() {
        Long holdingMinutes = getHoldingMinutes();
        Integer ltfMinutes = getLtfMinutes();

        if (holdingMinutes == null || ltfMinutes == null || ltfMinutes == 0) {
            return null;
        }

        return BigDecimal.valueOf(holdingMinutes)
                .divide(BigDecimal.valueOf(ltfMinutes), 1, RoundingMode.HALF_UP);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public LocalDateTime getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDateTime tradeDate) {
        this.tradeDate = tradeDate;
    }

    public LocalDateTime getEntryTime() {
        return entryTime;
    }

    public void setEntryTime(LocalDateTime entryTime) {
        this.entryTime = entryTime;
    }

    public LocalDateTime getExitTime() {
        return exitTime;
    }

    public void setExitTime(LocalDateTime exitTime) {
        this.exitTime = exitTime;
    }

    public String getAccountLabel() {
        return accountLabel;
    }

    public void setAccountLabel(String accountLabel) {
        this.accountLabel = accountLabel;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Setup getSetup() {
        return setup;
    }

    public void setSetup(Setup setup) {
        this.setup = setup;
    }

    public String getSetupName() {
        return setup != null ? setup.getName() : null;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getHtf() {
        return htf;
    }

    public void setHtf(String htf) {
        this.htf = htf;
    }

    public String getLtf() {
        return ltf;
    }

    public void setLtf(String ltf) {
        this.ltf = ltf;
    }

    public double getEntryPrice() {
        return entryPrice;
    }

    public void setEntryPrice(double entryPrice) {
        this.entryPrice = entryPrice;
    }

    public double getStopLoss() {
        return stopLoss;
    }

    public void setStopLoss(double stopLoss) {
        this.stopLoss = stopLoss;
    }

    public Double getInitialStopLoss() {
        return initialStopLoss;
    }

    public void setInitialStopLoss(Double initialStopLoss) {
        this.initialStopLoss = initialStopLoss;
    }

    public Boolean getInitialStopLossConfirmed() {
        return initialStopLossConfirmed;
    }

    public void setInitialStopLossConfirmed(Boolean initialStopLossConfirmed) {
        this.initialStopLossConfirmed = initialStopLossConfirmed;
    }

    @Transient
    public double getRiskStopLoss() {
        if (hasExactRiskBasis() && initialStopLoss != null && initialStopLoss > 0) {
            return initialStopLoss;
        }
        return stopLoss;
    }

    @Transient
    public boolean isImportedFromMt5() {
        return note != null && note.startsWith("Imported from MT5 history");
    }

    @Transient
    public boolean hasExactRiskBasis() {
        if (Boolean.TRUE.equals(initialStopLossConfirmed)) {
            return initialStopLoss != null && initialStopLoss > 0;
        }
        if (Boolean.FALSE.equals(initialStopLossConfirmed)) {
            return false;
        }
        if (isImportedFromMt5()) {
            return false;
        }
        return (initialStopLoss != null && initialStopLoss > 0) || stopLoss > 0;
    }

    @Transient
    public boolean isExactRiskBasis() {
        return hasExactRiskBasis();
    }

    public double getTakeProfit() {
        return takeProfit;
    }

    public void setTakeProfit(double takeProfit) {
        this.takeProfit = takeProfit;
    }

    public double getExitPrice() {
        return exitPrice;
    }

    public void setExitPrice(double exitPrice) {
        this.exitPrice = exitPrice;
    }

    public double getPositionSize() {
        return positionSize;
    }

    public void setPositionSize(double positionSize) {
        this.positionSize = positionSize;
    }

    public String getMt5PositionId() {
        return mt5PositionId;
    }

    public void setMt5PositionId(String mt5PositionId) {
        this.mt5PositionId = mt5PositionId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public double getPnl() {
        return pnl;
    }

    public void setPnl(double pnl) {
        this.pnl = pnl;
    }

    public double getRMultiple() {
        return rMultiple;
    }

    public void setRMultiple(double rMultiple) {
        this.rMultiple = rMultiple;
    }

    public String getRMultipleSource() {
        return rMultipleSource;
    }

    public void setRMultipleSource(String rMultipleSource) {
        this.rMultipleSource = rMultipleSource;
    }

    @Transient
    public boolean hasKnownRMultiple() {
        return rMultipleSource != null && !"UNKNOWN".equalsIgnoreCase(rMultipleSource);
    }

    @Transient
    public boolean hasEstimatedRMultiple() {
        return rMultipleSource != null && rMultipleSource.toUpperCase().startsWith("ESTIMATED");
    }

    @Transient
    public boolean hasExactRMultiple() {
        return "EXACT".equalsIgnoreCase(rMultipleSource);
    }

    @Transient
    public boolean isKnownRMultiple() {
        return hasKnownRMultiple();
    }

    @Transient
    public boolean isEstimatedRMultiple() {
        return hasEstimatedRMultiple();
    }

    @Transient
    public boolean isExactRMultiple() {
        return hasExactRMultiple();
    }

    @Transient
    public String getRMultipleSourceLabel() {
        if ("EXACT".equalsIgnoreCase(rMultipleSource)) {
            return "Exact";
        }
        if ("ESTIMATED_SETUP".equalsIgnoreCase(rMultipleSource)) {
            return "Estimated (Setup)";
        }
        if ("ESTIMATED_ACCOUNT".equalsIgnoreCase(rMultipleSource)) {
            return "Estimated (Account)";
        }
        return "Unknown";
    }

    public String getSession() {
        if (entryTime != null) {
            return inferSessionFromEntryTime(entryTime);
        }
        if (StringUtils.hasText(session)) {
            return session;
        }
        if (StringUtils.hasText(sessionGuess)) {
            return normalizeSessionLabel(sessionGuess);
        }
        return session;
    }

    public void setSession(String session) {
        this.session = session;
    }

    public Integer getEstimatedHoldingMinutes() {
        return estimatedHoldingMinutes;
    }

    public void setEstimatedHoldingMinutes(Integer estimatedHoldingMinutes) {
        this.estimatedHoldingMinutes = estimatedHoldingMinutes;
    }

    public Integer getEstimatedLtfCandlesHeld() {
        return estimatedLtfCandlesHeld;
    }

    public void setEstimatedLtfCandlesHeld(Integer estimatedLtfCandlesHeld) {
        this.estimatedLtfCandlesHeld = estimatedLtfCandlesHeld;
    }

    public String getSessionGuess() {
        return sessionGuess;
    }

    public void setSessionGuess(String sessionGuess) {
        this.sessionGuess = sessionGuess;
    }

    public String getSessionConfidence() {
        return sessionConfidence;
    }

    public void setSessionConfidence(String sessionConfidence) {
        this.sessionConfidence = sessionConfidence;
    }

    @Transient
    public boolean hasExactHoldingMinutes() {
        return getExactHoldingMinutes() != null;
    }

    @Transient
    public boolean hasEstimatedHoldingMinutes() {
        return getExactHoldingMinutes() == null && estimatedHoldingMinutes != null;
    }

    @Transient
    public String getHoldingMinutesSourceLabel() {
        if (hasExactHoldingMinutes()) {
            return "Exact";
        }
        if (hasEstimatedHoldingMinutes()) {
            return "Estimated";
        }
        return "Unknown";
    }

    @Transient
    public boolean hasEstimatedSession() {
        return entryTime == null && StringUtils.hasText(sessionGuess);
    }

    @Transient
    public String getSessionSourceLabel() {
        if (entryTime != null) {
            return "Exact";
        }
        if (hasEstimatedSession()) {
            return "Estimated";
        }
        if (StringUtils.hasText(session)) {
            return "Manual";
        }
        return "Unknown";
    }

    private String inferSessionFromEntryTime(LocalDateTime value) {
        int hour = value.getHour();
        if (hour >= 0 && hour < 7) {
            return "ASIA";
        }
        if (hour >= 7 && hour < 13) {
            return "LONDON";
        }
        if (hour >= 13 && hour < 22) {
            return "NEW_YORK";
        }
        return "OTHER";
    }

    private String normalizeSessionLabel(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String normalized = value.trim().toUpperCase().replace(' ', '_');
        if ("NEW_YORK".equals(normalized) || "LONDON".equals(normalized) || "ASIA".equals(normalized) || "OTHER".equals(normalized)) {
            return normalized;
        }
        return value.trim();
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public List<MistakeTag> getMistakes() {
        return mistakes;
    }

    public void setMistakes(List<MistakeTag> mistakes) {
        this.mistakes = mistakes;
    }
}
