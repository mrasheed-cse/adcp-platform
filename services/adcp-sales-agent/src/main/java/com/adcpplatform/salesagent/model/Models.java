package com.adcpplatform.salesagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * AdCP wire types. Field names are snake_case to match the AdCP JSON schemas.
 * These follow the AdCP 3.x shapes closely but are hand-written, so run the
 * conformance storyboard to catch any drift from the official schemas.
 */
public final class Models {

    private Models() {}

    // ---------- Catalog ----------

    public enum DeliveryType {
        @JsonProperty("guaranteed") GUARANTEED,
        @JsonProperty("non_guaranteed") NON_GUARANTEED
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PricingOption(
            @JsonProperty("pricing_option_id") String pricingOptionId,
            @JsonProperty("pricing_model") String pricingModel,      // "cpm"
            @JsonProperty("rate") BigDecimal rate,                   // fixed rate, e.g. 2.50 per 1000 impressions
            @JsonProperty("currency") String currency,
            @JsonProperty("is_fixed") boolean fixed,
            @JsonProperty("min_spend_per_package") BigDecimal minSpend) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Product(
            @JsonProperty("product_id") String productId,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("format_ids") List<String> formatIds,
            @JsonProperty("delivery_type") DeliveryType deliveryType,
            @JsonProperty("pricing_options") List<PricingOption> pricingOptions,
            @JsonProperty("countries") List<String> countries,
            @JsonProperty("estimated_daily_impressions") long estimatedDailyImpressions,
            // internal only: keywords used to match briefs, not sent to buyers
            @com.fasterxml.jackson.annotation.JsonIgnore List<String> keywords,
            // internal only: ad-server placements this product delivers on
            @com.fasterxml.jackson.annotation.JsonIgnore List<String> placements) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreativeFormat(
            @JsonProperty("format_id") String formatId,
            @JsonProperty("name") String name,
            @JsonProperty("type") String type,          // display | video | native
            @JsonProperty("width") Integer width,
            @JsonProperty("height") Integer height,
            @JsonProperty("max_duration_seconds") Integer maxDurationSeconds) {}

    // ---------- Media buy input ----------

    public record PackageRequest(
            @JsonProperty(value = "product_id", required = true)
            @JsonPropertyDescription("product_id from get_products")
            String productId,
            @JsonProperty(value = "pricing_option_id", required = true)
            @JsonPropertyDescription("pricing_option_id from the chosen product's pricing_options")
            String pricingOptionId,
            @JsonProperty(value = "budget", required = true)
            @JsonPropertyDescription("Budget for this package in the pricing option's currency")
            BigDecimal budget,
            @JsonProperty("buyer_ref")
            @JsonPropertyDescription("Optional buyer-side reference for this package")
            String buyerRef) {}

    // ---------- Media buy state ----------

    public enum MediaBuyStatus {
        @JsonProperty("pending_activation") PENDING_ACTIVATION,
        @JsonProperty("active") ACTIVE,
        @JsonProperty("paused") PAUSED,
        @JsonProperty("completed") COMPLETED,
        @JsonProperty("canceled") CANCELED
    }

    /** How far the media buy has been set up in the ad server (trafficking API). */
    public enum Provisioning {
        @JsonProperty("pending") PENDING,
        @JsonProperty("provisioned") PROVISIONED,
        @JsonProperty("failed") FAILED
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MediaBuyPackage(
            @JsonProperty("package_id") String packageId,
            @JsonProperty("product_id") String productId,
            @JsonProperty("pricing_option_id") String pricingOptionId,
            @JsonProperty("budget") BigDecimal budget,
            @JsonProperty("buyer_ref") String buyerRef,
            @JsonProperty("creative_ids") List<String> creativeIds,
            // internal: CPM agreed at booking, and the ad-server line item that delivers this package
            @com.fasterxml.jackson.annotation.JsonIgnore BigDecimal rate,
            @com.fasterxml.jackson.annotation.JsonIgnore String lineItemId) {

        public MediaBuyPackage {
            creativeIds = creativeIds == null ? List.of() : List.copyOf(creativeIds);
        }

        public MediaBuyPackage withLineItemId(String id) {
            return new MediaBuyPackage(packageId, productId, pricingOptionId, budget, buyerRef, creativeIds, rate, id);
        }

        public MediaBuyPackage withCreativeIds(List<String> ids) {
            return new MediaBuyPackage(packageId, productId, pricingOptionId, budget, buyerRef, ids, rate, lineItemId);
        }

        /** Impression goal implied by budget and CPM: budget / rate * 1000. Zero rate = no goal. */
        public long impressionGoal() {
            if (rate == null || rate.signum() == 0) {
                return 0;
            }
            return budget.multiply(BigDecimal.valueOf(1000)).divide(rate, 0, java.math.RoundingMode.DOWN).longValue();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MediaBuy(
            @JsonProperty("media_buy_id") String mediaBuyId,
            @JsonProperty("brand") String brand,
            @JsonProperty("status") MediaBuyStatus status,
            @JsonProperty("start_time") Instant startTime,
            @JsonProperty("end_time") Instant endTime,
            @JsonProperty("total_budget") BigDecimal totalBudget,
            @JsonProperty("currency") String currency,
            @JsonProperty("packages") List<MediaBuyPackage> packages,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("provisioning") Provisioning provisioning,
            @JsonProperty("provisioning_error") String provisioningError,
            // internal bookkeeping, never sent to buyers
            @com.fasterxml.jackson.annotation.JsonIgnore String accountId,
            @com.fasterxml.jackson.annotation.JsonIgnore String idempotencyKey,
            @com.fasterxml.jackson.annotation.JsonIgnore String requestHash,
            @com.fasterxml.jackson.annotation.JsonIgnore long version,
            // true while the ad server has not caught up with a change
            @com.fasterxml.jackson.annotation.JsonIgnore boolean dirty,
            @com.fasterxml.jackson.annotation.JsonIgnore String traffickingAdvertiserId,
            @com.fasterxml.jackson.annotation.JsonIgnore String traffickingOrderId) {

        public MediaBuy {
            packages = List.copyOf(packages);
        }

        /** New status; version bumped for optimistic locking; marked for syncing to the ad server. */
        public MediaBuy withStatus(MediaBuyStatus newStatus, Instant at) {
            return new MediaBuy(mediaBuyId, brand, newStatus, startTime, endTime, totalBudget, currency, packages,
                    createdAt, at, provisioning, provisioningError, accountId, idempotencyKey, requestHash,
                    version + 1, true, traffickingAdvertiserId, traffickingOrderId);
        }

        /** New packages (e.g. creative assignments); marked for syncing. */
        public MediaBuy withPackages(List<MediaBuyPackage> newPackages, Instant at) {
            return new MediaBuy(mediaBuyId, brand, status, startTime, endTime, totalBudget, currency, newPackages,
                    createdAt, at, provisioning, provisioningError, accountId, idempotencyKey, requestHash,
                    version + 1, true, traffickingAdvertiserId, traffickingOrderId);
        }

        /** Result of a sync with the ad server. */
        public MediaBuy withSync(Provisioning p, String error, boolean stillDirty, String advertiserId, String orderId,
                                 List<MediaBuyPackage> syncedPackages, Instant at) {
            return new MediaBuy(mediaBuyId, brand, status, startTime, endTime, totalBudget, currency, syncedPackages,
                    createdAt, at, p, error, accountId, idempotencyKey, requestHash, version + 1, stillDirty,
                    advertiserId, orderId);
        }

        public java.util.Optional<MediaBuyPackage> pkg(String packageId) {
            return packages.stream().filter(p -> p.packageId().equals(packageId)).findFirst();
        }
    }

    /** A creative as the buyer synced it (simplified AdCP sync_creatives input). */
    public record CreativeInput(
            @JsonProperty(value = "creative_id", required = true)
            @JsonPropertyDescription("Your id for this creative; reuse it to assign the same creative again")
            String creativeId,
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty(value = "format_id", required = true)
            @JsonPropertyDescription("A format_id from list_creative_formats")
            String formatId,
            @JsonProperty("image_url")
            @JsonPropertyDescription("For display formats: https URL of the image")
            String imageUrl,
            @JsonProperty("html")
            @JsonPropertyDescription("For native formats, or display creatives delivered as HTML")
            String html,
            @JsonProperty(value = "click_url", required = true)
            @JsonPropertyDescription("Landing page opened on click (http or https)")
            String clickUrl) {}

    /** Stored creative, with the ad-server creative id once synced. */
    public record SyncedCreative(String accountId, String creativeId, String name, String formatId, String imageUrl,
                                 String html, String clickUrl, String traffickingCreativeId, Instant createdAt) {

        public boolean sameContent(CreativeInput in) {
            return java.util.Objects.equals(name, in.name()) && java.util.Objects.equals(formatId, in.formatId())
                    && java.util.Objects.equals(imageUrl, in.imageUrl()) && java.util.Objects.equals(html, in.html())
                    && java.util.Objects.equals(clickUrl, in.clickUrl());
        }
    }

    // ---------- Delivery ----------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeliveryTotals(
            @JsonProperty("impressions") long impressions,
            @JsonProperty("clicks") Long clicks,   // null until click reporting is wired to ClickHouse
            @JsonProperty("spend") BigDecimal spend,
            @JsonProperty("ctr") Double ctr) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PackageDelivery(
            @JsonProperty("package_id") String packageId,
            @JsonProperty("impressions") long impressions,
            @JsonProperty("clicks") Long clicks,
            @JsonProperty("spend") BigDecimal spend,
            @JsonProperty("pacing_index") double pacingIndex) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MediaBuyDelivery(
            @JsonProperty("media_buy_id") String mediaBuyId,
            @JsonProperty("status") MediaBuyStatus status,
            @JsonProperty("reporting_period_start") Instant periodStart,
            @JsonProperty("reporting_period_end") Instant periodEnd,
            @JsonProperty("currency") String currency,
            @JsonProperty("totals") DeliveryTotals totals,
            @JsonProperty("by_package") List<PackageDelivery> byPackage) {}

    // ---------- Errors / envelopes ----------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdcpError(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message,
            @JsonProperty("field") String field) {}
}
