package com.tradejournal.repository;

import com.tradejournal.entity.TradeReview;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TradeReview review where review.trade.id = :tradeId")
    void deleteByTradeId(@Param("tradeId") String tradeId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TradeReview review where review.trade.id in :tradeIds")
    void deleteByTradeIdIn(@Param("tradeIds") List<String> tradeIds);
}
