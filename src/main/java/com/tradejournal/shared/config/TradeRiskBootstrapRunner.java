package com.tradejournal.config;

import com.tradejournal.service.TradeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class TradeRiskBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TradeRiskBootstrapRunner.class);

    private final TradeService tradeService;

    public TradeRiskBootstrapRunner(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        tradeService.refreshAllUsersRMultiples();
        log.info("Trade risk bootstrap refreshed R multiple sources for existing trades.");
    }
}
