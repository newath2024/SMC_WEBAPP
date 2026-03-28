package com.tradejournal.settings.service;

import com.tradejournal.billing.domain.BillingInvoice;
import com.tradejournal.billing.domain.BillingSubscription;
import com.tradejournal.setup.domain.Setup;
import com.tradejournal.trade.domain.Trade;
import com.tradejournal.trade.domain.TradeImage;
import com.tradejournal.trade.domain.TradeMistakeTag;
import com.tradejournal.trade.domain.TradeReview;
import com.tradejournal.auth.domain.User;
import com.tradejournal.billing.repository.BillingInvoiceRepository;
import com.tradejournal.billing.repository.BillingSubscriptionRepository;
import com.tradejournal.mistake.repository.MistakeTagRepository;
import com.tradejournal.setup.repository.SetupRepository;
import com.tradejournal.trade.repository.TradeImageRepository;
import com.tradejournal.trade.repository.TradeMistakeTagRepository;
import com.tradejournal.trade.repository.TradeRepository;
import com.tradejournal.trade.repository.TradeReviewRepository;
import com.tradejournal.trade.service.TradeImageService;
import com.tradejournal.auth.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AccountPrivacyService {

    private static final DateTimeFormatter EXPORT_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final TradeRepository tradeRepository;
    private final TradeReviewRepository tradeReviewRepository;
    private final TradeImageRepository tradeImageRepository;
    private final TradeMistakeTagRepository tradeMistakeTagRepository;
    private final MistakeTagRepository mistakeTagRepository;
    private final SetupRepository setupRepository;
    private final BillingInvoiceRepository billingInvoiceRepository;
    private final BillingSubscriptionRepository billingSubscriptionRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final TradeImageService tradeImageService;

    public AccountPrivacyService(
            TradeRepository tradeRepository,
            TradeReviewRepository tradeReviewRepository,
            TradeImageRepository tradeImageRepository,
            TradeMistakeTagRepository tradeMistakeTagRepository,
            MistakeTagRepository mistakeTagRepository,
            SetupRepository setupRepository,
            BillingInvoiceRepository billingInvoiceRepository,
            BillingSubscriptionRepository billingSubscriptionRepository,
            UserRepository userRepository,
            TradeImageService tradeImageService
    ) {
        this.tradeRepository = tradeRepository;
        this.tradeReviewRepository = tradeReviewRepository;
        this.tradeImageRepository = tradeImageRepository;
        this.tradeMistakeTagRepository = tradeMistakeTagRepository;
        this.mistakeTagRepository = mistakeTagRepository;
        this.setupRepository = setupRepository;
        this.billingInvoiceRepository = billingInvoiceRepository;
        this.billingSubscriptionRepository = billingSubscriptionRepository;
        this.userRepository = userRepository;
        this.objectMapper = new ObjectMapper();
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.tradeImageService = tradeImageService;
    }

    @Transactional(readOnly = true)
    public ExportFile exportAllData(User currentUser) {
        AccountSnapshot snapshot = buildSnapshot(currentUser);
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
            return new ExportFile(
                    buildFileName("tradejournal_account_export", "json"),
                    "application/json",
                    json.getBytes()
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not export account data");
        }
    }

    @Transactional(readOnly = true)
    public ExportFile exportTradesCsv(User currentUser) {
        AccountSnapshot snapshot = buildSnapshot(currentUser);
        StringBuilder csv = new StringBuilder();
        csv.append("Trade ID,Entry Time,Exit Time,Account,Setup,Symbol,Direction,HTF,LTF,Entry Price,Stop Loss,Take Profit,Exit Price,Position Size,Result,PnL,R Multiple,R Source,Session,Country Timezone,Mistakes,Note\n");
        for (TradeExport trade : snapshot.trades()) {
            csv.append(csv(trade.id())).append(",")
                    .append(csv(trade.entryTime())).append(",")
                    .append(csv(trade.exitTime())).append(",")
                    .append(csv(trade.accountLabel())).append(",")
                    .append(csv(trade.setupName())).append(",")
                    .append(csv(trade.symbol())).append(",")
                    .append(csv(trade.direction())).append(",")
                    .append(csv(trade.htf())).append(",")
                    .append(csv(trade.ltf())).append(",")
                    .append(trade.entryPrice()).append(",")
                    .append(trade.stopLoss()).append(",")
                    .append(trade.takeProfit()).append(",")
                    .append(trade.exitPrice()).append(",")
                    .append(trade.positionSize()).append(",")
                    .append(csv(trade.result())).append(",")
                    .append(trade.pnl()).append(",")
                    .append(trade.rMultiple()).append(",")
                    .append(csv(trade.rMultipleSource())).append(",")
                    .append(csv(trade.session())).append(",")
                    .append(csv(snapshot.profile().timezone())).append(",")
                    .append(csv(String.join(" | ", trade.mistakes()))).append(",")
                    .append(csv(trade.note()))
                    .append("\n");
        }
        return new ExportFile(
                buildFileName("tradejournal_trades_export", "csv"),
                "text/csv",
                csv.toString().getBytes()
        );
    }

    @Transactional
    public void deleteAccount(User currentUser, String currentPassword, String confirmationText) {
        String passwordValue = normalize(currentPassword);
        String confirmationValue = normalize(confirmationText);

        if (passwordValue.isBlank()) {
            throw new IllegalArgumentException("Current password is required to delete your account");
        }

        if (!passwordEncoder.matches(passwordValue, currentUser.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }

        if (!"DELETE".equalsIgnoreCase(confirmationValue)) {
            throw new IllegalArgumentException("Type DELETE to confirm account deletion");
        }

        List<Trade> trades = tradeRepository.findByUserIdOrderByEntryTimeDesc(currentUser.getId());
        List<String> tradeIds = trades.stream().map(Trade::getId).toList();

        if (!tradeIds.isEmpty()) {
            for (String tradeId : tradeIds) {
                tradeImageService.deleteByTradeId(tradeId);
            }
            tradeReviewRepository.deleteByTradeIdIn(tradeIds);
            tradeMistakeTagRepository.deleteByTradeIdIn(tradeIds);
            tradeRepository.deleteByUserId(currentUser.getId());
        }

        billingInvoiceRepository.deleteByUserId(currentUser.getId());
        billingSubscriptionRepository.deleteByUserId(currentUser.getId());
        setupRepository.deleteByUserId(currentUser.getId());
        mistakeTagRepository.deleteByUserId(currentUser.getId());
        userRepository.deleteById(currentUser.getId());
    }

    private AccountSnapshot buildSnapshot(User currentUser) {
        List<Trade> trades = tradeRepository.findByUserIdOrderByEntryTimeDesc(currentUser.getId());
        List<String> tradeIds = trades.stream().map(Trade::getId).toList();
        List<TradeReview> reviews = tradeIds.isEmpty() ? List.of() : tradeReviewRepository.findByTradeIdIn(tradeIds);
        List<TradeImage> images = tradeIds.isEmpty() ? List.of() : tradeImageRepository.findByTradeIdIn(tradeIds);
        List<TradeMistakeTag> mistakeLinks = tradeIds.isEmpty() ? List.of() : tradeMistakeTagRepository.findByTradeIdIn(tradeIds);
        List<Setup> setups = new ArrayList<>(setupRepository.findByUserIdOrderByCreatedAtDesc(currentUser.getId()));
        setups.sort(Comparator.comparing(Setup::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        List<BillingInvoice> invoices = billingInvoiceRepository.findByUserIdOrderByInvoiceDateDesc(currentUser.getId());
        Optional<BillingSubscription> subscription = billingSubscriptionRepository.findByUserId(currentUser.getId());

        Map<String, List<String>> mistakesByTradeId = mistakeLinks.stream()
                .collect(Collectors.groupingBy(
                        link -> link.getTrade().getId(),
                        Collectors.mapping(link -> link.getMistakeTag().getName(), Collectors.toList())
                ));

        Map<String, TradeReview> reviewsByTradeId = reviews.stream()
                .collect(Collectors.toMap(review -> review.getTrade().getId(), Function.identity()));

        List<TradeExport> tradeExports = trades.stream()
                .map(trade -> new TradeExport(
                        trade.getId(),
                        formatDateTime(trade.getEntryTime()),
                        formatDateTime(trade.getExitTime()),
                        trade.getTradeDate() != null ? trade.getTradeDate().toString() : null,
                        trade.getAccountLabel(),
                        trade.getSetup() != null ? trade.getSetup().getId() : null,
                        trade.getSetupName(),
                        trade.getSymbol(),
                        trade.getDirection(),
                        trade.getHtf(),
                        trade.getLtf(),
                        trade.getEntryPrice(),
                        trade.getStopLoss(),
                        trade.getTakeProfit(),
                        trade.getExitPrice(),
                        trade.getPositionSize(),
                        trade.getResult(),
                        trade.getPnl(),
                        trade.getRMultiple(),
                        trade.getRMultipleSource(),
                        trade.getSession(),
                        trade.getNote(),
                        mistakesByTradeId.getOrDefault(trade.getId(), List.of()),
                        reviewSummary(reviewsByTradeId.get(trade.getId()))
                ))
                .toList();

        List<ReviewExport> reviewExports = reviews.stream()
                .map(review -> new ReviewExport(
                        review.getTrade().getId(),
                        review.getQualityScore(),
                        review.getFollowedPlan(),
                        review.getHadConfirmation(),
                        review.getRespectedRisk(),
                        review.getAlignedHtfBias(),
                        review.getCorrectSession(),
                        review.getCorrectSetup(),
                        review.getCorrectPoi(),
                        review.getHadFomo(),
                        review.getEnteredBeforeNews(),
                        review.getEntryTimingRating(),
                        review.getExitQualityRating(),
                        review.getWouldTakeAgain(),
                        review.getPreTradeChecklist(),
                        review.getPostTradeReview(),
                        review.getLessonLearned(),
                        review.getImprovementNote(),
                        formatDateTime(review.getCreatedAt()),
                        formatDateTime(review.getUpdatedAt())
                ))
                .toList();

        List<ImageExport> imageExports = images.stream()
                .map(image -> new ImageExport(
                        image.getId(),
                        image.getTrade().getId(),
                        image.getImageType(),
                        image.getCaption(),
                        image.getImageUrl(),
                        formatDateTime(image.getCreatedAt())
                ))
                .toList();

        List<SetupExport> setupExports = setups.stream()
                .map(setup -> new SetupExport(
                        setup.getId(),
                        setup.getName(),
                        setup.getDescription(),
                        setup.isActive(),
                        formatDateTime(setup.getCreatedAt()),
                        formatDateTime(setup.getUpdatedAt())
                ))
                .toList();

        List<InvoiceExport> invoiceExports = invoices.stream()
                .map(invoice -> new InvoiceExport(
                        invoice.getInvoiceNo(),
                        invoice.getAmount(),
                        invoice.getInvoiceDate() != null ? invoice.getInvoiceDate().toString() : null,
                        invoice.getBillingPeriod(),
                        invoice.getStatus() != null ? invoice.getStatus().name() : null,
                        invoice.getPaymentMethod(),
                        invoice.getDownloadLabel()
                ))
                .toList();

        SubscriptionExport subscriptionExport = subscription
                .map(value -> new SubscriptionExport(
                        value.getProvider(),
                        value.getPlan() != null ? value.getPlan().name() : null,
                        value.getStatus() != null ? value.getStatus().name() : null,
                        value.getBillingCycle() != null ? value.getBillingCycle().name() : null,
                        value.getStartDate() != null ? value.getStartDate().toString() : null,
                        value.getNextBillingDate() != null ? value.getNextBillingDate().toString() : null,
                        value.getAmount(),
                        value.getPaymentStatus() != null ? value.getPaymentStatus().name() : null
                ))
                .orElse(null);

        return new AccountSnapshot(
                LocalDateTime.now().toString(),
                new ProfileExport(
                        currentUser.getId(),
                        currentUser.getUsername(),
                        currentUser.getEmail(),
                        currentUser.getRole(),
                        currentUser.getPlanType() != null ? currentUser.getPlanType().name() : null,
                        currentUser.getTimezone(),
                        currentUser.getCountry(),
                        currentUser.getDefaultAccount(),
                        currentUser.getPreferredCurrency(),
                        currentUser.getRiskUnit(),
                        currentUser.getChartTimezone(),
                        currentUser.getEmailNotificationsEnabled(),
                        currentUser.getWeeklySummaryEnabled(),
                        currentUser.getBillingNotificationsEnabled(),
                        currentUser.getAvatarDataUrl(),
                        formatDateTime(currentUser.getCreatedAt()),
                        formatDateTime(currentUser.getUpdatedAt())
                ),
                setupExports,
                tradeExports,
                reviewExports,
                imageExports,
                invoiceExports,
                subscriptionExport
        );
    }

    private String reviewSummary(TradeReview review) {
        if (review == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (review.getQualityScore() != null) {
            parts.add("Quality score: " + review.getQualityScore());
        }
        if (Boolean.TRUE.equals(review.getFollowedPlan())) {
            parts.add("Followed plan");
        }
        if (Boolean.TRUE.equals(review.getWouldTakeAgain())) {
            parts.add("Would take again");
        }
        return parts.isEmpty() ? "Review attached" : String.join(" | ", parts);
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private String buildFileName(String prefix, String extension) {
        return prefix + "_" + LocalDateTime.now().format(EXPORT_TIMESTAMP_FORMAT) + "." + extension;
    }

    private String csv(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record ExportFile(String fileName, String contentType, byte[] content) {
    }

    private record AccountSnapshot(
            String exportedAt,
            ProfileExport profile,
            List<SetupExport> setups,
            List<TradeExport> trades,
            List<ReviewExport> reviews,
            List<ImageExport> images,
            List<InvoiceExport> invoices,
            SubscriptionExport subscription
    ) {
    }

    private record ProfileExport(
            String id,
            String username,
            String email,
            String role,
            String planType,
            String timezone,
            String country,
            String defaultAccount,
            String preferredCurrency,
            String riskUnit,
            String chartTimezone,
            Boolean emailNotificationsEnabled,
            Boolean weeklySummaryEnabled,
            Boolean billingNotificationsEnabled,
            String avatarDataUrl,
            String createdAt,
            String updatedAt
    ) {
    }

    private record SetupExport(
            String id,
            String name,
            String description,
            boolean active,
            String createdAt,
            String updatedAt
    ) {
    }

    private record TradeExport(
            String id,
            String entryTime,
            String exitTime,
            String tradeDate,
            String accountLabel,
            String setupId,
            String setupName,
            String symbol,
            String direction,
            String htf,
            String ltf,
            double entryPrice,
            double stopLoss,
            double takeProfit,
            double exitPrice,
            double positionSize,
            String result,
            double pnl,
            double rMultiple,
            String rMultipleSource,
            String session,
            String note,
            List<String> mistakes,
            String reviewSummary
    ) {
    }

    private record ReviewExport(
            String tradeId,
            Integer qualityScore,
            Boolean followedPlan,
            Boolean hadConfirmation,
            Boolean respectedRisk,
            Boolean alignedHtfBias,
            Boolean correctSession,
            Boolean correctSetup,
            Boolean correctPoi,
            Boolean hadFomo,
            Boolean enteredBeforeNews,
            Integer entryTimingRating,
            Integer exitQualityRating,
            Boolean wouldTakeAgain,
            String preTradeChecklist,
            String postTradeReview,
            String lessonLearned,
            String improvementNote,
            String createdAt,
            String updatedAt
    ) {
    }

    private record ImageExport(
            String id,
            String tradeId,
            String imageType,
            String caption,
            String imageUrl,
            String createdAt
    ) {
    }

    private record InvoiceExport(
            String invoiceNo,
            double amount,
            String invoiceDate,
            String billingPeriod,
            String status,
            String paymentMethod,
            String downloadLabel
    ) {
    }

    private record SubscriptionExport(
            String provider,
            String plan,
            String status,
            String billingCycle,
            String startDate,
            String nextBillingDate,
            double amount,
            String paymentStatus
    ) {
    }
}
