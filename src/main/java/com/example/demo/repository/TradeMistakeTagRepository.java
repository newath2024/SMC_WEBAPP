package com.example.demo.repository;

import com.example.demo.entity.TradeMistakeTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeMistakeTagRepository extends JpaRepository<TradeMistakeTag, String> {

    List<TradeMistakeTag> findByTradeId(String tradeId);

    void deleteByTradeId(String tradeId);

    boolean existsByTradeIdAndMistakeTagId(String tradeId, String mistakeTagId);
}