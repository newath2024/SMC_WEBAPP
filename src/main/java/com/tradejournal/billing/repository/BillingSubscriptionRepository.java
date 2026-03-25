package com.tradejournal.repository;

import com.tradejournal.entity.BillingSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BillingSubscriptionRepository extends JpaRepository<BillingSubscription, String> {
    Optional<BillingSubscription> findByUserId(String userId);
    Optional<BillingSubscription> findByProviderCustomerId(String providerCustomerId);
    Optional<BillingSubscription> findByProviderSubscriptionId(String providerSubscriptionId);
    List<BillingSubscription> findAllByOrderByUpdatedAtDesc();
    void deleteByUserId(String userId);
}
