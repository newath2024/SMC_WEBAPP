package com.example.demo.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "trades")
public class Trade {

    @Id
    @Column(length = 36)
    private String id;

    private LocalDateTime tradeDate;

    @NotNull
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime entryTime;

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

    @Positive
    private double takeProfit;

    private double exitPrice;

    @Positive
    private double positionSize;

    private String result; // WIN / LOSS / BE and take partial

    private double pnl;

    @Column(name = "r_multiple")
    private double rMultiple;

    private String session; // ASIA / LONDON / NEW_YORK / OTHER

    @Column(columnDefinition = "TEXT")
    private String note;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (tradeDate == null && entryTime != null) tradeDate = entryTime;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
        if (tradeDate == null && entryTime != null) tradeDate = entryTime;
    }

    @Transient
    public Long getHoldingMinutes() {
        if (entryTime == null || exitTime == null) {
            return null;
        }
        return Duration.between(entryTime, exitTime).toMinutes();
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

    public String getSession() {
        return session;
    }

    public void setSession(String session) {
        this.session = session;
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
}