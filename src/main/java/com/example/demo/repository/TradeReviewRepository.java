package com.example.demo.repository;

import com.example.demo.entity.TradeReview;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradeReviewRepository extends JpaRepository<TradeReview, String> {

    @EntityGraph(attributePaths = {"trade"})
    Optional<TradeReview> findByTradeId(String tradeId);

    @EntityGraph(attributePaths = {"trade"})
    Optional<TradeReview> findByTradeIdAndTradeUserId(String tradeId, String userId);

    @EntityGraph(attributePaths = {"trade"})
    List<TradeReview> findByTradeIdIn(List<String> tradeIds);

    @EntityGraph(attributePaths = {"trade"})
    Optional<TradeReview> findTopByTradeUserIdAndQualityScoreIsNotNullOrderByUpdatedAtDesc(String userId);

    void deleteByTradeId(String tradeId);

    void deleteByTradeIdIn(List<String> tradeIds);
}
