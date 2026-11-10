package com.adcpplatform.salesagent.tools;

import com.adcpplatform.salesagent.catalog.ProductCatalog;
import com.adcpplatform.salesagent.config.AccountResolver;
import com.adcpplatform.salesagent.config.AdcpProperties;
import com.adcpplatform.salesagent.adserver.Reconciler;
import com.adcpplatform.salesagent.creative.CreativeService;
import com.adcpplatform.salesagent.delivery.DeliveryReporter;
import com.adcpplatform.salesagent.mediabuy.AdcpException;
import com.adcpplatform.salesagent.mediabuy.MediaBuyService;
import com.adcpplatform.salesagent.model.Models.CreativeInput;
import com.adcpplatform.salesagent.model.Models.MediaBuy;
import com.adcpplatform.salesagent.model.Models.PackageRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The AdCP sales-agent surface. Each @Tool becomes an MCP tool with the exact AdCP task name.
 * Business-rule failures are raised as AdcpToolError so the MCP result is flagged isError=true,
 * with the AdCP {"errors":[{code,message,field}]} JSON as its text.
 */
@Component
public class AdcpSalesTools {

    private static final Logger log = LoggerFactory.getLogger(AdcpSalesTools.class);

    private final ProductCatalog catalog;
    private final MediaBuyService mediaBuys;
    private final DeliveryReporter delivery;
    private final Reconciler reconciler;
    private final CreativeService creativeService;
    private final AdcpProperties props;
    private final AccountResolver accounts;
    private final ObjectMapper json;

    public AdcpSalesTools(ProductCatalog catalog, MediaBuyService mediaBuys, DeliveryReporter delivery,
                          Reconciler reconciler, CreativeService creativeService,
                          AdcpProperties props, AccountResolver accounts, ObjectMapper json) {
        this.catalog = catalog;
        this.mediaBuys = mediaBuys;
        this.delivery = delivery;
        this.reconciler = reconciler;
        this.creativeService = creativeService;
        this.props = props;
        this.accounts = accounts;
        this.json = json;
    }

    // ---------------- Discovery ----------------

    @Tool(name = "get_adcp_capabilities",
          description = "Describe which AdCP protocols and tasks this agent supports. Call this first.")
    public Map<String, Object> getAdcpCapabilities() {
        return Map.of(
                "adcp", Map.of("major_versions", List.of(3), "spec_version", props.specVersion()),
                "supported_protocols", List.of("media_buy"),
                "specialisms", List.of("sales-guaranteed", "sales-non-guaranteed"),
                "media_buy", Map.of(
                        "tasks", List.of("get_products", "list_creative_formats", "create_media_buy",
                                "sync_creatives", "update_media_buy", "get_media_buys", "get_media_buy_delivery"),
                        "pricing_models", List.of("cpm"),
                        "currencies", List.of("USD"),
                        "countries", List.of("BD")));
    }

    @Tool(name = "get_products",
          description = "Find advertising products that match a natural-language campaign brief. "
                  + "Returns products with product_id, formats, delivery_type and pricing_options.")
    public Map<String, Object> getProducts(
            @ToolParam(description = "Natural-language brief, e.g. 'reach cricket fans in Bangladesh with video'",
                       required = false) String brief,
            @ToolParam(description = "Buying mode, e.g. 'brief' or 'wholesale'", required = false) String buying_mode) {
        return call("get_products", account -> Map.of("products", catalog.search(brief)));
    }

    @Tool(name = "list_creative_formats",
          description = "List creative formats (sizes, video durations, native) accepted on this inventory.")
    public Map<String, Object> listCreativeFormats() {
        return Map.of("formats", catalog.formats());
    }

    // ---------------- Media buys ----------------

    @Tool(name = "create_media_buy",
          description = "Book a campaign. Each package references a product_id and one of its pricing_option_id values. "
                  + "idempotency_key (16-255 chars) is required: retrying the identical request with the same key "
                  + "returns the original buy; reusing a key with a different request is rejected.")
    public Map<String, Object> createMediaBuy(
            @ToolParam(description = "Advertiser brand, as a domain such as acme.com") String brand,
            @ToolParam(description = "ISO 8601 start time, or 'asap'") String start_time,
            @ToolParam(description = "ISO 8601 end time") String end_time,
            @ToolParam(description = "Packages to buy") List<PackageRequest> packages,
            @ToolParam(description = "Unique key for this buy, 16-255 characters, reused only on retries") String idempotency_key) {
        return call("create_media_buy", account -> {
            var result = mediaBuys.create(account, brand, start_time, end_time, packages, idempotency_key);
            MediaBuy synced = sync(result.mediaBuy());
            Map<String, Object> body = new LinkedHashMap<>(asMap(synced));
            body.put("replayed", result.replayed());
            return body;
        });
    }

    @Tool(name = "update_media_buy",
          description = "Pause, resume, or cancel an existing media buy.")
    public Map<String, Object> updateMediaBuy(
            @ToolParam(description = "media_buy_id returned by create_media_buy") String media_buy_id,
            @ToolParam(description = "true to pause, false to resume", required = false) Boolean paused,
            @ToolParam(description = "true to cancel the buy permanently", required = false) Boolean canceled) {
        return call("update_media_buy", account -> {
            MediaBuy updated;
            if (Boolean.TRUE.equals(canceled)) {
                updated = mediaBuys.cancel(account, media_buy_id);
            } else if (paused != null) {
                updated = mediaBuys.setPaused(account, media_buy_id, paused);
            } else {
                throw new AdcpException("INVALID_REQUEST", "provide paused or canceled", "paused");
            }
            return asMap(sync(updated));
        });
    }

    @Tool(name = "sync_creatives",
          description = "Upload creatives and assign them to packages of your media buys; a package starts serving "
                  + "once it has a creative. Creatives are immutable: to change one, use a new creative_id. "
                  + "Video formats are not served yet.")
    public Map<String, Object> syncCreatives(
            @ToolParam(description = "Creatives to add (existing creative_ids with identical content are left unchanged)",
                       required = false) List<CreativeInput> creatives,
            @ToolParam(description = "Map of creative_id to the package_ids it should run on", required = false)
            Map<String, List<String>> assignments) {
        return call("sync_creatives", account -> {
            CreativeService.SyncResult r = creativeService.sync(account, creatives, assignments);
            List<Map<String, Object>> buys = r.mediaBuyIds().stream().map(id -> {
                MediaBuy b = sync(mediaBuys.get(account, id));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("media_buy_id", b.mediaBuyId());
                m.put("provisioning", b.provisioning());
                if (b.provisioningError() != null) {
                    m.put("provisioning_error", b.provisioningError());
                }
                return m;
            }).toList();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("creatives", r.creatives());
            body.put("assignments", r.assignments().stream()
                    .map(a -> Map.of("creative_id", a.creativeId(), "package_id", a.packageId())).toList());
            body.put("media_buys", buys);
            return body;
        });
    }

    @Tool(name = "get_media_buys",
          description = "Get the current state of your media buys. Omit media_buy_ids to list all of them.")
    public Map<String, Object> getMediaBuys(
            @ToolParam(description = "Specific media_buy_id values to fetch", required = false) List<String> media_buy_ids) {
        return call("get_media_buys", account -> Map.of("media_buys", mediaBuys.list(account, media_buy_ids)));
    }

    @Tool(name = "get_media_buy_delivery",
          description = "Get delivery (impressions, clicks, spend, pacing) for a media buy, in total and per package.")
    public Map<String, Object> getMediaBuyDelivery(
            @ToolParam(description = "media_buy_id to report on") String media_buy_id) {
        return call("get_media_buy_delivery", account ->
                Map.of("media_buy_deliveries", List.of(delivery.report(mediaBuys.get(account, media_buy_id)))));
    }

    // ---------------- helpers ----------------

    /**
     * Pushes the buy to the ad server right away so the response shows the outcome. Failures are recorded on
     * the buy (provisioning = pending or failed) and retried in the background; they never fail the call.
     */
    private MediaBuy sync(MediaBuy buy) {
        try {
            return reconciler.reconcile(buy.mediaBuyId());
        } catch (RuntimeException e) {
            log.warn("immediate sync of {} failed; background retry will pick it up", buy.mediaBuyId(), e);
            return buy;
        }
    }

    private Map<String, Object> call(String task, Function<String, Map<String, Object>> body) {
        String account = null;
        try {
            account = accounts.currentAccount();
            Map<String, Object> result = body.apply(account);
            log.info("adcp task={} account={} ok", task, account);
            return result;
        } catch (AdcpException e) {
            log.info("adcp task={} account={} rejected: {}", task, account, e.errors());
            throw new AdcpToolError(toJson(Map.of("errors", e.errors())), e);
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"errors\":[{\"code\":\"INTERNAL_ERROR\",\"message\":\"failed to serialize error\"}]}";
        }
    }

    private static Map<String, Object> asMap(MediaBuy buy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("media_buy", buy);
        m.put("media_buy_id", buy.mediaBuyId());
        m.put("status", buy.status());
        return m;
    }
}
