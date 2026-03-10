package com.example.demo.repository;

import com.example.demo.entity.TradeMistakeTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeMistakeTagRepository extends JpaRepository<TradeMistakeTag, String> {

    List<TradeMistakeTag> findByTradeId(String tradeId);

    List<TradeMistakeTag> findByTradeIdIn(List<String> tradeIds);

    void deleteByTradeId(String tradeId);

    void deleteByMistakeTagId(String mistakeTagId);

    boolean existsByTradeIdAndMistakeTagId(String tradeId, String mistakeTagId);
}
