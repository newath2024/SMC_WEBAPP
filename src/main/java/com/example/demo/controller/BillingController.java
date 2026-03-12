package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.repository.TradeImageRepository;
import com.example.demo.service.TradeService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Controller
public class BillingController {

    private static final DateTimeFormatter BILL_DATE_FORMAT = DateTimeFormatter.ofPattern("MMM dd, yyyy");
    private static final double GB_TO_MB = 1024.0;

    private final UserService userService;
    private final TradeService tradeService;
    private final TradeImageRepository tradeImageRepository;

    public BillingController(
            UserService userService,
            TradeService tradeService,
            TradeImageRepository tradeImageRepository
    ) {
        this.userService = userService;
        this.tradeService = tradeService;
        this.tradeImageRepository = tradeImageRepository;
    }

    @GetMapping("/billing")
    public String billingPage(Model model, HttpSession session) {
        User currentUser = userService.getCurrentUser(session);
        if (currentUser == null) {
            return "redirect:/login";
        }

        boolean hasProAccess = userService.hasProAccess(currentUser);
        int tradeLimit = userService.resolveTradeLimit(currentUser);
        int tradeUsageRaw = tradeService.findAllByUser(currentUser.getId()).size();
        int tradesThisMonth = Math.max(3, Math.min(tradeUsageRaw, hasProAccess ? 96 : 100));
        int tradeUsageForSidebar = hasProAccess ? tradeUsageRaw : Math.min(tradeUsageRaw, tradeLimit);
        LocalDate today = LocalDate.now();
        TrialInfo trialInfo = resolveTrialInfo(currentUser, hasProAccess, today);
        String billingStatus = resolveBillingStatus(currentUser, hasProAccess, trialInfo);
        String billingStatusCss = resolveStatusCss(billingStatus);

        long imageCount = tradeImageRepository.countByTradeUserId(currentUser.getId());
        double estimatedStorageUsedGb = estimateStorageUsageGb(tradeUsageRaw, imageCount);
        double storageLimitGb = hasProAccess ? 10.0 : 2.0;
        int storageUsagePct = (int) Math.min(100, Math.round((estimatedStorageUsedGb / storageLimitGb) * 100));
        int tradeUsagePct = hasProAccess ? Math.min(100, Math.round((tradesThisMonth * 100.0f) / 500.0f)) : (int) Math.min(100, Math.round((tradesThisMonth * 100.0) / tradeLimit));
        int imageLimit = hasProAccess ? 250 : 50;
        int imageUsagePct = (int) Math.min(100, Math.round((imageCount * 100.0) / imageLimit));
        int exportLimit = hasProAccess ? 25 : 10;
        int exportsUsed = hasProAccess
                ? Math.max(3, Math.min(exportLimit, (tradeUsageRaw / 12) + 2))
                : Math.min(exportLimit, Math.max(1, tradeUsageRaw / 25));
        int exportUsagePct = (int) Math.min(100, Math.round((exportsUsed * 100.0) / exportLimit));

        LocalDate nextBillingDate = hasProAccess ? today.plusDays(30) : (trialInfo.inTrial() ? trialInfo.endsOn() : today.plusDays(14));
        String currentPlan = hasProAccess ? "Pro Plan" : "Standard Plan";
        String monthlyPrice = hasProAccess ? "$29 / month" : "$0 / month";
        boolean hasCardOnFile = hasProAccess;
        String paymentBrand = hasCardOnFile ? "Visa" : "No card";
        String paymentMasked = hasCardOnFile ? "**** 4242" : "No card on file";
        String paymentExpiry = hasCardOnFile ? "09/28" : "N/A";
        String renewalType = hasCardOnFile ? "Auto-renew via Visa" : "Manual renewal via crypto";
        String billingDateLabel = hasCardOnFile ? "Next billing" : "Expiration date";
        String invoicePaymentMethod = hasCardOnFile ? "Visa" : "Crypto";
        String planStarted = currentUser.getCreatedAt() != null
                ? currentUser.getCreatedAt().toLocalDate().format(BILL_DATE_FORMAT)
                : today.minusDays(30).format(BILL_DATE_FORMAT);
        String cryptoNetwork = "USDT (TRC20)";
        String cryptoWalletAddress = "0x470526fc610709483153098833f09d9f9322f19c";
        String cryptoInvoiceStatus = "Pending";
        String storageUsedLabel = formatStorageUsage(estimatedStorageUsedGb);
        String storageLimitLabel = hasProAccess ? "10 GB" : "2 GB";
        String screenshotsUsedLabel = imageCount + " / " + imageLimit;
        String tradesUsedLabel = tradesThisMonth + " / " + (hasProAccess ? "500" : tradeLimit);
        String exportsUsedLabel = String.valueOf(exportsUsed);

        model.addAttribute("currentUser", currentUser);
        model.addAttribute("hasProAccess", hasProAccess);
        model.addAttribute("tradeUsage", tradeUsageForSidebar);
        model.addAttribute("tradeUsageLimit", hasProAccess ? 0 : tradeLimit);

        model.addAttribute("currentPlan", currentPlan);
        model.addAttribute("billingStatus", billingStatus);
        model.addAttribute("billingStatusCss", billingStatusCss);
        model.addAttribute("monthlyPrice", monthlyPrice);
        model.addAttribute("renewalType", renewalType);
        model.addAttribute("billingDateLabel", billingDateLabel);
        model.addAttribute("nextBillingDate", nextBillingDate != null ? nextBillingDate.format(BILL_DATE_FORMAT) : "No upcoming charge");
        model.addAttribute("planStarted", planStarted);
        model.addAttribute("showTrialInfo", trialInfo.inTrial());
        model.addAttribute("trialDaysRemaining", trialInfo.daysRemaining());

        model.addAttribute("tradeUsageRaw", tradeUsageRaw);
        model.addAttribute("tradesUsedLabel", tradesUsedLabel);
        model.addAttribute("tradeUsagePct", tradeUsagePct);
        model.addAttribute("imageCount", imageCount);
        model.addAttribute("storageUsedLabel", storageUsedLabel);
        model.addAttribute("storageLimitLabel", storageLimitLabel);
        model.addAttribute("storageUsagePct", storageUsagePct);
        model.addAttribute("imagesLimit", imageLimit);
        model.addAttribute("screenshotsUsedLabel", screenshotsUsedLabel);
        model.addAttribute("imagesUsagePct", imageUsagePct);
        model.addAttribute("exportsUsed", exportsUsed);
        model.addAttribute("exportsUsedLabel", exportsUsedLabel);
        model.addAttribute("exportLimitLabel", String.valueOf(exportLimit));
        model.addAttribute("exportUsagePct", exportUsagePct);

        model.addAttribute("hasCardOnFile", hasCardOnFile);
        model.addAttribute("paymentBrand", paymentBrand);
        model.addAttribute("paymentMasked", paymentMasked);
        model.addAttribute("paymentExpiry", paymentExpiry);
        model.addAttribute("showUpgradeCard", !hasProAccess);
        model.addAttribute("showManageActions", hasProAccess);
        model.addAttribute("cryptoNetwork", cryptoNetwork);
        model.addAttribute("cryptoWalletAddress", cryptoWalletAddress);
        model.addAttribute("cryptoInvoiceStatus", cryptoInvoiceStatus);
        model.addAttribute("cryptoCountdownSeconds", 1800);
        model.addAttribute("supportedCoins", List.of("USDT", "USDC", "BTC", "ETH"));
        model.addAttribute("paymentMethodOptions", List.of("USDT (TRC20)", "USDT (ERC20)", "USDC", "BTC", "ETH"));

        model.addAttribute("invoices", buildInvoices(hasProAccess, invoicePaymentMethod));
        return "billing";
    }

    private TrialInfo resolveTrialInfo(User currentUser, boolean hasProAccess, LocalDate today) {
        if (hasProAccess || currentUser.getCreatedAt() == null) {
            return new TrialInfo(false, 0, null);
        }
        LocalDate trialEnd = currentUser.getCreatedAt().toLocalDate().plusDays(7);
        if (!today.isBefore(trialEnd)) {
            return new TrialInfo(false, 0, trialEnd);
        }
        int daysRemaining = (int) Math.max(1, ChronoUnit.DAYS.between(today, trialEnd));
        return new TrialInfo(true, daysRemaining, trialEnd);
    }

    private String resolveBillingStatus(User currentUser, boolean hasProAccess, TrialInfo trialInfo) {
        if (!currentUser.isActive()) {
            return "Canceled";
        }
        if (trialInfo.inTrial()) {
            return "Trial";
        }
        if (hasProAccess) {
            return "Active";
        }
        return "Active";
    }

    private String resolveStatusCss(String billingStatus) {
        return switch (billingStatus) {
            case "Trial" -> "is-trial";
            case "Past Due" -> "is-past-due";
            case "Canceled" -> "is-canceled";
            default -> "is-active";
        };
    }

    private double estimateStorageUsageGb(int tradeCount, long imageCount) {
        double tradeMetadataGb = tradeCount * 0.00008;
        double screenshotGb = imageCount * 0.0025;
        return Math.max(0.05, tradeMetadataGb + screenshotGb);
    }

    private String formatStorageUsage(double estimatedStorageUsedGb) {
        double storageUsedMb = estimatedStorageUsedGb * GB_TO_MB;
        if (storageUsedMb < GB_TO_MB) {
            return Math.round(storageUsedMb) + "MB";
        }
        return String.format("%.1fGB", estimatedStorageUsedGb);
    }

    private List<InvoiceRow> buildInvoices(boolean hasProAccess, String paymentMethod) {
        List<InvoiceRow> invoices = new ArrayList<>();
        invoices.add(new InvoiceRow("INV-2026-0301", "$29", "Visa", "USD", "Mar 01, 2026", "Paid", "card_4242", "#"));
        invoices.add(new InvoiceRow("INV-2026-0401", "$29", "Crypto", "USDT-TRC20", "Apr 01, 2026", "Paid", "tx_0xabc8d91", "#"));
        invoices.add(new InvoiceRow("INV-2026-0501", hasProAccess ? "$29" : "$0", hasProAccess ? paymentMethod : "Crypto", hasProAccess ? "USD" : "USDC", "May 01, 2026", hasProAccess ? "Pending" : "Awaiting Confirmation", hasProAccess ? "card_4242" : "tx_pending_19f2", "#"));
        invoices.add(new InvoiceRow("INV-2026-0201", "$29", "Crypto", "BTC", "Feb 01, 2026", "Expired", "tx_expired_4ae1", "#"));
        return invoices;
    }

    public record InvoiceRow(
            String invoiceId,
            String amount,
            String paymentMethod,
            String currency,
            String date,
            String status,
            String reference,
            String downloadUrl
    ) {
    }

    public record TrialInfo(
            boolean inTrial,
            int daysRemaining,
            LocalDate endsOn
    ) {
    }
}
