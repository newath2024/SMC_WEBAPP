package com.example.demo.repository;

import com.example.demo.entity.TradeImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeImageRepository extends JpaRepository<TradeImage, String> {

    List<TradeImage> findByTradeIdOrderByCreatedAtAsc(String tradeId);
}