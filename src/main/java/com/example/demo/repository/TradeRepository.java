package com.example.demo.repository;

import com.example.demo.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradeRepository extends JpaRepository<Trade, String> {

    List<Trade> findByUserIdOrderByEntryTimeDesc(String userId);

    Optional<Trade> findByIdAndUserId(String id, String userId);
}