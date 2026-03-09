package com.example.demo.repository;

import com.example.demo.entity.TradeReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradeReviewRepository extends JpaRepository<TradeReview, String> {

    Optional<TradeReview> findByTradeId(String tradeId);

    Optional<TradeReview> findByTradeIdAndTradeUserId(String tradeId, String userId);

    List<TradeReview> findByTradeIdIn(List<String> tradeIds);

    void deleteByTradeId(String tradeId);
}
