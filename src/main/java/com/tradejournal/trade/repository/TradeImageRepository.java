package com.tradejournal.trade.repository;

import com.tradejournal.trade.domain.TradeImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TradeImageRepository extends JpaRepository<TradeImage, String> {

    List<TradeImage> findByTradeIdOrderByCreatedAtAsc(String tradeId);

    List<TradeImage> findByTradeIdIn(List<String> tradeIds);

    Optional<TradeImage> findByIdAndTradeId(String id, String tradeId);

    long countByTradeUserId(String userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TradeImage image where image.trade.id = :tradeId")
    void deleteByTradeId(@Param("tradeId") String tradeId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TradeImage image where image.trade.id in :tradeIds")
    void deleteByTradeIdIn(@Param("tradeIds") List<String> tradeIds);
}
