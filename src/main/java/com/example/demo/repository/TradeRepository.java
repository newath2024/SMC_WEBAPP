package com.example.demo.repository;

import com.example.demo.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<Trade, String> {
}