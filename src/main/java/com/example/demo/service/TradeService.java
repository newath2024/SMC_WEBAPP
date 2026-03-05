package com.example.demo.service;

import com.example.demo.entity.Trade;
import com.example.demo.repository.TradeRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TradeService {

    private final TradeRepository repo;

    public TradeService(TradeRepository repo) {
        this.repo = repo;
    }

    public Trade save(Trade trade) {
        return repo.save(trade);
    }

    public List<Trade> findAll() {
        return repo.findAll();
    }

    public Trade findById(String id) {
        return repo.findById(id).orElseThrow();
    }

    public void delete(String id) {
        repo.deleteById(id);
    }
}