package com.example.demo.service;

import org.springframework.util.StringUtils;

import java.time.LocalDate;

public record TradeFilterCriteria(
        String view,
        String setup,
        String session,
        String symbol,
        String result,
        String mistake,
        LocalDate from,
        LocalDate to
) {

    public boolean aiReviewedOnly() {
        return "ai-reviewed".equalsIgnoreCase(normalize(view));
    }

    public boolean hasActiveFilters() {
        return aiReviewedOnly()
                || StringUtils.hasText(setup)
                || StringUtils.hasText(session)
                || StringUtils.hasText(symbol)
                || StringUtils.hasText(result)
                || StringUtils.hasText(mistake)
                || from != null
                || to != null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
