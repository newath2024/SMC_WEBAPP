package com.example.demo.repository;

import com.example.demo.entity.TradeImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradeImageRepository extends JpaRepository<TradeImage, String> {

    List<TradeImage> findByTradeIdOrderByCreatedAtAsc(String tradeId);

    Optional<TradeImage> findByIdAndTradeId(String id, String tradeId);

    void deleteByTradeId(String tradeId);
}
