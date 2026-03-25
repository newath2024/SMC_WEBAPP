package com.tradejournal.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "trade_mistake_tags",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_trade_mistake_unique",
                        columnNames = {"trade_id", "mistake_tag_id"}
                )
        }
)
public class TradeMistakeTag {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trade_id", nullable = false)
    private Trade trade;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mistake_tag_id", nullable = false)
    private MistakeTag mistakeTag;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
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

    public MistakeTag getMistakeTag() {
        return mistakeTag;
    }

    public void setMistakeTag(MistakeTag mistakeTag) {
        this.mistakeTag = mistakeTag;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
