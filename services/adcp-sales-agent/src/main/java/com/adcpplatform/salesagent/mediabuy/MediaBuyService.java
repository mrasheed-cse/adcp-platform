package com.adcpplatform.salesagent.mediabuy;

import com.adcpplatform.salesagent.catalog.ProductCatalog;
import com.adcpplatform.salesagent.model.Models.AdcpError;
import com.adcpplatform.salesagent.model.Models.MediaBuy;
import com.adcpplatform.salesagent.model.Models.MediaBuyPackage;
import com.adcpplatform.salesagent.model.Models.MediaBuyStatus;
import com.adcpplatform.salesagent.model.Models.PackageRequest;
import com.adcpplatform.salesagent.model.Models.PricingOption;
import com.adcpplatform.salesagent.model.Models.Provisioning;
import com.adcpplatform.salesagent.model.Models.Product;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Service
public class MediaBuyService {

    private static final int MAX_UPDATE_ATTEMPTS = 3;

    private static final Map<MediaBuyStatus, Set<MediaBuyStatus>> ALLOWED_TRANSITIONS = Map.of(
            MediaBuyStatus.PENDING_ACTIVATION, EnumSet.of(MediaBuyStatus.ACTIVE, MediaBuyStatus.PAUSED, MediaBuyStatus.CANCELED),
            MediaBuyStatus.ACTIVE, EnumSet.of(MediaBuyStatus.PAUSED, MediaBuyStatus.COMPLETED, MediaBuyStatus.CANCELED),
            MediaBuyStatus.PAUSED, EnumSet.of(MediaBuyStatus.ACTIVE, MediaBuyStatus.PENDING_ACTIVATION,
                    MediaBuyStatus.COMPLETED, MediaBuyStatus.CANCELED),
            MediaBuyStatus.COMPLETED, EnumSet.noneOf(MediaBuyStatus.class),
            MediaBuyStatus.CANCELED, EnumSet.noneOf(MediaBuyStatus.class));

    private final ProductCatalog catalog;
    private final MediaBuyRepository repository;
    private final Clock clock;

    public MediaBuyService(ProductCatalog catalog, MediaBuyRepository repository) {
        this(catalog, repository, Clock.systemUTC());
    }

    MediaBuyService(ProductCatalog catalog, MediaBuyRepository repository, Clock clock) {
        this.catalog = catalog;
        this.repository = repository;
        this.clock = clock;
    }

    public record CreateResult(MediaBuy mediaBuy, boolean replayed) {}

    // ---------------------------------------------------------------- create

    public CreateResult create(String accountId, String brand, String startTime, String endTime,
                               List<PackageRequest> packages, String idempotencyKey) {
        List<AdcpError> errors = new ArrayList<>();

        if (brand == null || brand.isBlank()) {
            errors.add(new AdcpError("INVALID_REQUEST", "brand is required (e.g. a domain like acme.com)", "brand"));
        }
        if (idempotencyKey == null || idempotencyKey.length() < 16 || idempotencyKey.length() > 255) {
            errors.add(new AdcpError("INVALID_REQUEST", "idempotency_key must be 16-255 characters", "idempotency_key"));
        }
        if (!errors.isEmpty()) throw new AdcpException(errors);

        // Idempotency is scoped to the authenticated account, never to caller-supplied fields.
        String requestHash = fingerprint(brand, startTime, endTime, packages);
        var existing = repository.findByIdempotencyKey(accountId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        Instant now = clock.instant();
        Instant start = parseTime(startTime, "start_time", now, errors);
        Instant end = parseTime(endTime, "end_time", null, errors);
        if (start != null && end != null && !end.isAfter(start)) {
            errors.add(new AdcpError("INVALID_REQUEST", "end_time must be after start_time", "end_time"));
        }
        if (end != null && end.isBefore(now)) {
            errors.add(new AdcpError("INVALID_REQUEST", "end_time is in the past", "end_time"));
        }
        if (packages == null || packages.isEmpty()) {
            errors.add(new AdcpError("INVALID_REQUEST", "at least one package is required", "packages"));
        }

        List<MediaBuyPackage> accepted = new ArrayList<>();
        String currency = null;
        BigDecimal total = BigDecimal.ZERO;

        if (packages != null) {
            for (int i = 0; i < packages.size(); i++) {
                PackageRequest req = packages.get(i);
                String path = "packages[" + i + "]";
                if (req == null) {
                    errors.add(new AdcpError("INVALID_REQUEST", "package must not be null", path));
                    continue;
                }
                Product product = catalog.find(req.productId()).orElse(null);
                if (product == null) {
                    errors.add(new AdcpError("PRODUCT_NOT_FOUND", "unknown product_id " + req.productId(), path + ".product_id"));
                    continue;
                }
                PricingOption option = product.pricingOptions().stream()
                        .filter(po -> po.pricingOptionId().equals(req.pricingOptionId()))
                        .findFirst().orElse(null);
                if (option == null) {
                    errors.add(new AdcpError("INVALID_PRICING_OPTION",
                            "pricing_option_id " + req.pricingOptionId() + " is not offered on " + product.productId(),
                            path + ".pricing_option_id"));
                    continue;
                }
                if (req.budget() == null || req.budget().signum() <= 0) {
                    errors.add(new AdcpError("INVALID_REQUEST", "budget must be positive", path + ".budget"));
                    continue;
                }
                if (option.minSpend() != null && req.budget().compareTo(option.minSpend()) < 0) {
                    errors.add(new AdcpError("BUDGET_TOO_LOW",
                            "minimum spend for " + product.productId() + " is " + option.minSpend() + " " + option.currency(),
                            path + ".budget"));
                    continue;
                }
                if (currency != null && !currency.equals(option.currency())) {
                    errors.add(new AdcpError("INVALID_REQUEST", "all packages must use the same currency", path));
                    continue;
                }
                currency = option.currency();
                total = total.add(req.budget());
                accepted.add(new MediaBuyPackage("pkg_" + shortId(), product.productId(),
                        option.pricingOptionId(), req.budget(), req.buyerRef(), List.of(), option.rate(), null));
            }
        }
        if (!errors.isEmpty()) throw new AdcpException(errors);

        MediaBuyStatus status = start.isAfter(now) ? MediaBuyStatus.PENDING_ACTIVATION : MediaBuyStatus.ACTIVE;
        // New buys start dirty: the reconciler sets them up in the ad server.
        MediaBuy buy = new MediaBuy("mb_" + shortId(), brand, status, start, end, total, currency,
                List.copyOf(accepted), now, now, Provisioning.PENDING, null, accountId, idempotencyKey, requestHash,
                0, true, null, null);

        MediaBuy stored = repository.saveIfKeyAbsent(buy);
        // Lost a race to a concurrent request with the same key: apply the same replay rules.
        return stored.mediaBuyId().equals(buy.mediaBuyId()) ? new CreateResult(stored, false) : replay(stored, requestHash);
    }

    private static CreateResult replay(MediaBuy original, String requestHash) {
        if (!original.requestHash().equals(requestHash)) {
            throw new AdcpException("IDEMPOTENCY_CONFLICT",
                    "idempotency_key was already used with a different request (media_buy_id "
                            + original.mediaBuyId() + "); use a new key for a new buy", "idempotency_key");
        }
        return new CreateResult(original, true);
    }

    // ---------------------------------------------------------------- reads (side-effect free)

    public MediaBuy get(String accountId, String mediaBuyId) {
        return repository.findById(mediaBuyId)
                .filter(b -> b.accountId().equals(accountId))   // other accounts' buys look non-existent
                .orElseThrow(() -> new AdcpException("MEDIA_BUY_NOT_FOUND",
                        "unknown media_buy_id " + mediaBuyId, "media_buy_id"));
    }

    public Collection<MediaBuy> list(String accountId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return repository.findByAccount(accountId);
        }
        return ids.stream().map(id -> get(accountId, id)).toList();
    }

    // ---------------------------------------------------------------- updates (optimistic locking)

    public MediaBuy setPaused(String accountId, String mediaBuyId, boolean paused) {
        return update(accountId, mediaBuyId, buy -> {
            Instant now = clock.instant();
            if (paused) return MediaBuyStatus.PAUSED;
            if (!buy.endTime().isAfter(now)) {
                throw new AdcpException("INVALID_STATE", "flight has already ended; cannot resume", "paused");
            }
            return buy.startTime().isAfter(now) ? MediaBuyStatus.PENDING_ACTIVATION : MediaBuyStatus.ACTIVE;
        });
    }

    public MediaBuy cancel(String accountId, String mediaBuyId) {
        return update(accountId, mediaBuyId, buy -> MediaBuyStatus.CANCELED);
    }

    private MediaBuy update(String accountId, String mediaBuyId, Function<MediaBuy, MediaBuyStatus> targetFor) {
        for (int attempt = 1; attempt <= MAX_UPDATE_ATTEMPTS; attempt++) {
            MediaBuy current = get(accountId, mediaBuyId);
            MediaBuyStatus target = targetFor.apply(current);
            if (current.status() == target) return current;
            checkTransition(current.status(), target);

            MediaBuy next = current.withStatus(target, clock.instant());
            if (repository.updateIfVersion(next, current.version())) {
                return next;
            }
            // Someone else changed it between our read and write: re-read and re-validate.
        }
        throw new AdcpException("CONFLICT",
                "media buy " + mediaBuyId + " is being modified concurrently; retry", "media_buy_id");
    }

    /**
     * Applies a change to one media buy with optimistic locking, retrying on concurrent updates.
     * Used for creative assignments and ad-server sync results.
     */
    public MediaBuy modify(String mediaBuyId, java.util.function.UnaryOperator<MediaBuy> change) {
        for (int attempt = 1; attempt <= MAX_UPDATE_ATTEMPTS; attempt++) {
            MediaBuy current = repository.findById(mediaBuyId)
                    .orElseThrow(() -> new AdcpException("MEDIA_BUY_NOT_FOUND", "unknown media_buy_id " + mediaBuyId, "media_buy_id"));
            MediaBuy next = change.apply(current);
            if (next == current || repository.updateIfVersion(next, current.version())) {
                return next;
            }
        }
        throw new AdcpException("CONFLICT", "media buy " + mediaBuyId + " is being modified concurrently; retry", "media_buy_id");
    }

    public java.time.Instant now() {
        return clock.instant();
    }

    // ---------------------------------------------------------------- time-based transitions

    /**
     * pending -> active once start passes; active/paused -> completed once end passes.
     * Called by StatusScheduler. A lost optimistic-lock race is skipped and picked up on the next run.
     *
     * @return number of buys whose status changed
     */
    public int advanceTimeBasedStatuses() {
        Instant now = clock.instant();
        int changed = 0;
        for (MediaBuy buy : repository.findOpen()) {
            MediaBuyStatus next = buy.status();
            if (next == MediaBuyStatus.PENDING_ACTIVATION && !buy.startTime().isAfter(now)) {
                next = MediaBuyStatus.ACTIVE;
            }
            if ((next == MediaBuyStatus.ACTIVE || next == MediaBuyStatus.PAUSED) && !buy.endTime().isAfter(now)) {
                next = MediaBuyStatus.COMPLETED;
            }
            if (next != buy.status() && repository.updateIfVersion(buy.withStatus(next, now), buy.version())) {
                changed++;
            }
        }
        return changed;
    }

    // ---------------------------------------------------------------- helpers

    private static void checkTransition(MediaBuyStatus from, MediaBuyStatus to) {
        if (!ALLOWED_TRANSITIONS.get(from).contains(to)) {
            throw new AdcpException("INVALID_STATE",
                    "cannot move media buy from " + from.name().toLowerCase() + " to " + to.name().toLowerCase(),
                    "status");
        }
    }

    /** Stable hash of the buyer's raw request, used to detect key reuse with a different payload. */
    static String fingerprint(String brand, String start, String end, List<PackageRequest> packages) {
        StringBuilder sb = new StringBuilder()
                .append(brand).append('\n').append(start).append('\n').append(end).append('\n');
        if (packages != null) {
            for (PackageRequest p : packages) {
                if (p == null) { sb.append("null\n"); continue; }
                sb.append(p.productId()).append('|')
                  .append(p.pricingOptionId()).append('|')
                  .append(p.budget() == null ? "null" : p.budget().stripTrailingZeros().toPlainString()).append('|')
                  .append(p.buyerRef()).append('\n');
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static Instant parseTime(String value, String field, Instant asapValue, List<AdcpError> errors) {
        if (value == null || value.isBlank()) {
            errors.add(new AdcpError("INVALID_REQUEST", field + " is required", field));
            return null;
        }
        if ("asap".equalsIgnoreCase(value)) {
            if (asapValue == null) {
                errors.add(new AdcpError("INVALID_REQUEST", "\"asap\" is only valid for start_time", field));
            }
            return asapValue;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            errors.add(new AdcpError("INVALID_REQUEST", field + " must be ISO 8601 (e.g. 2026-11-01T00:00:00Z)", field));
            return null;
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
