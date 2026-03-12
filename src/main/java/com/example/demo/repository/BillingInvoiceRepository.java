package com.example.demo.repository;

import com.example.demo.entity.BillingInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BillingInvoiceRepository extends JpaRepository<BillingInvoice, String> {
    List<BillingInvoice> findTop200ByOrderByInvoiceDateDesc();
    boolean existsByInvoiceNo(String invoiceNo);
    Optional<BillingInvoice> findByInvoiceNo(String invoiceNo);
}
