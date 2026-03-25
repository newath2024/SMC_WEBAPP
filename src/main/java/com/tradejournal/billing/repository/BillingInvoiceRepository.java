package com.tradejournal.billing.repository;

import com.tradejournal.billing.domain.BillingInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BillingInvoiceRepository extends JpaRepository<BillingInvoice, String> {
    List<BillingInvoice> findTop200ByOrderByInvoiceDateDesc();
    List<BillingInvoice> findByUserIdOrderByInvoiceDateDesc(String userId);
    boolean existsByInvoiceNo(String invoiceNo);
    Optional<BillingInvoice> findByInvoiceNo(String invoiceNo);
    void deleteByUserId(String userId);
}
