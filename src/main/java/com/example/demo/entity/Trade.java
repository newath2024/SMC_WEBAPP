package com.example.demo.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
@Entity
@Table(name = "trades")
public class Trade {

    @Id
    @Column(length = 36)
    private String id;

    @NotBlank
    private String account;

    @NotBlank
    private String symbol;

    @NotBlank
    private String setup;

    @NotNull
    @Positive
    private BigDecimal entryPrice;

    @NotNull
    @Positive
    private BigDecimal stopLoss;
    private BigDecimal tp1Price;
    private BigDecimal tpFullPrice;

    private String result; // WIN / LOSS / BE
    private BigDecimal rMultiple;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    // getters/setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getSetup() { return setup; }
    public void setSetup(String setup) { this.setup = setup; }

    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }

    public BigDecimal getStopLoss() { return stopLoss; }
    public void setStopLoss(BigDecimal stopLoss) { this.stopLoss = stopLoss; }

    public BigDecimal getTp1Price() { return tp1Price; }
    public void setTp1Price(BigDecimal tp1Price) { this.tp1Price = tp1Price; }

    public BigDecimal getTpFullPrice() { return tpFullPrice; }
    public void setTpFullPrice(BigDecimal tpFullPrice) { this.tpFullPrice = tpFullPrice; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public BigDecimal getRMultiple() { return rMultiple; }
    public void setRMultiple(BigDecimal rMultiple) { this.rMultiple = rMultiple; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}