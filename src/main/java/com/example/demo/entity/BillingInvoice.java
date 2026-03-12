package com.example.demo.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "billing_invoices")
public class BillingInvoice {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, unique = true, length = 64)
    private String invoiceNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private double amount;

    @Column(nullable = false)
    private LocalDate invoiceDate;

    @Column(nullable = false, length = 32)
    private String billingPeriod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillingPaymentStatus status = BillingPaymentStatus.PENDING;

    @Column(nullable = false, length = 32)
    private String paymentMethod;

    @Column(nullable = false, length = 16)
    private String downloadLabel = "PDF";

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public String getId() { return id; }
    public String getInvoiceNo() { return invoiceNo; }
    public void setInvoiceNo(String invoiceNo) { this.invoiceNo = invoiceNo; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(LocalDate invoiceDate) { this.invoiceDate = invoiceDate; }
    public String getBillingPeriod() { return billingPeriod; }
    public void setBillingPeriod(String billingPeriod) { this.billingPeriod = billingPeriod; }
    public BillingPaymentStatus getStatus() { return status; }
    public void setStatus(BillingPaymentStatus status) { this.status = status; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getDownloadLabel() { return downloadLabel; }
    public void setDownloadLabel(String downloadLabel) { this.downloadLabel = downloadLabel; }
}
