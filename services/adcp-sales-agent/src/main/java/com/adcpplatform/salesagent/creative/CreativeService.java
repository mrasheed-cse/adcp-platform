package com.adcpplatform.salesagent.creative;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.adcpplatform.salesagent.catalog.ProductCatalog;
import com.adcpplatform.salesagent.mediabuy.AdcpException;
import com.adcpplatform.salesagent.mediabuy.MediaBuyService;
import com.adcpplatform.salesagent.model.Models.AdcpError;
import com.adcpplatform.salesagent.model.Models.CreativeFormat;
import com.adcpplatform.salesagent.model.Models.CreativeInput;
import com.adcpplatform.salesagent.model.Models.MediaBuy;
import com.adcpplatform.salesagent.model.Models.MediaBuyPackage;
import com.adcpplatform.salesagent.model.Models.MediaBuyStatus;
import com.adcpplatform.salesagent.model.Models.Product;
import com.adcpplatform.salesagent.model.Models.SyncedCreative;

/**
 * Simplified AdCP sync_creatives: stores the buyer's creatives and assigns them to packages.
 * Everything is validated before anything is written, so a rejected call changes nothing.
 * Creatives are immutable: to change one, sync it under a new creative_id.
 */
@Service
public class CreativeService {

    public record CreativeResult(String creativeId, String action) {}

    public record Assignment(String creativeId, String packageId, String mediaBuyId) {}

    public record SyncResult(List<CreativeResult> creatives, List<Assignment> assignments, Set<String> mediaBuyIds) {}

    private final CreativeRepository creatives;
    private final MediaBuyService mediaBuys;
    private final ProductCatalog catalog;

    public CreativeService(CreativeRepository creatives, MediaBuyService mediaBuys, ProductCatalog catalog) {
        this.creatives = creatives;
        this.mediaBuys = mediaBuys;
        this.catalog = catalog;
    }

    public SyncResult sync(String accountId, List<CreativeInput> inputs, Map<String, List<String>> assignments) {
        List<CreativeInput> in = inputs == null ? List.of() : inputs;
        Map<String, List<String>> assign = assignments == null ? Map.of() : assignments;
        List<AdcpError> errors = new ArrayList<>();

        // 1. Validate creatives.
        Map<String, CreativeInput> byId = new LinkedHashMap<>();
        for (int i = 0; i < in.size(); i++) {
            CreativeInput c = in.get(i);
            String path = "creatives[" + i + "]";
            if (c == null || blank(c.creativeId())) {
                errors.add(new AdcpError("INVALID_REQUEST", "creative_id is required", path + ".creative_id"));
                continue;
            }
            if (byId.putIfAbsent(c.creativeId(), c) != null) {
                errors.add(new AdcpError("INVALID_REQUEST", "duplicate creative_id " + c.creativeId(), path + ".creative_id"));
                continue;
            }
            validateCreative(c, path, errors);
            Optional<SyncedCreative> existing = creatives.find(accountId, c.creativeId());
            if (existing.isPresent() && !existing.get().sameContent(c)) {
                errors.add(new AdcpError("CREATIVE_IMMUTABLE",
                        "creative " + c.creativeId() + " already exists with different content; use a new creative_id", path));
            }
        }

        // 2. Validate assignments against the account's media buys.
        Map<String, MediaBuy> buyByPackage = new LinkedHashMap<>();
        for (MediaBuy b : mediaBuys.list(accountId, null)) {
            b.packages().forEach(p -> buyByPackage.put(p.packageId(), b));
        }
        List<Assignment> planned = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : assign.entrySet()) {
            String creativeId = e.getKey();
            String path = "assignments." + creativeId;
            String formatId = Optional.ofNullable(byId.get(creativeId)).map(CreativeInput::formatId)
                    .or(() -> creatives.find(accountId, creativeId).map(SyncedCreative::formatId)).orElse(null);
            if (formatId == null) {
                errors.add(new AdcpError("CREATIVE_NOT_FOUND", "creative " + creativeId + " is not in this call and was never synced", path));
                continue;
            }
            for (String packageId : e.getValue() == null ? List.<String>of() : e.getValue()) {
                MediaBuy buy = buyByPackage.get(packageId);
                if (buy == null) {
                    errors.add(new AdcpError("PACKAGE_NOT_FOUND", "unknown package_id " + packageId, path));
                    continue;
                }
                if (buy.status() == MediaBuyStatus.CANCELED || buy.status() == MediaBuyStatus.COMPLETED) {
                    errors.add(new AdcpError("INVALID_STATE", "media buy " + buy.mediaBuyId() + " is " + buy.status().name().toLowerCase(), path));
                    continue;
                }
                MediaBuyPackage pkg = buy.pkg(packageId).orElseThrow();
                Product product = catalog.find(pkg.productId()).orElseThrow();
                if (!product.formatIds().contains(formatId)) {
                    errors.add(new AdcpError("FORMAT_NOT_ACCEPTED", "package " + packageId + " (" + product.productId()
                            + ") accepts " + product.formatIds() + ", not " + formatId, path));
                    continue;
                }
                planned.add(new Assignment(creativeId, packageId, buy.mediaBuyId()));
            }
        }
        if (!errors.isEmpty()) {
            throw new AdcpException(errors);
        }

        // 3. Write creatives, then assignments (one optimistic update per media buy).
        List<CreativeResult> results = new ArrayList<>();
        for (CreativeInput c : byId.values()) {
            boolean created = creatives.insertIfAbsent(new SyncedCreative(accountId, c.creativeId(), c.name(), c.formatId(),
                    emptyToNull(c.imageUrl()), emptyToNull(c.html()), c.clickUrl(), null, mediaBuys.now()));
            results.add(new CreativeResult(c.creativeId(), created ? "created" : "unchanged"));
        }
        Map<String, List<Assignment>> byBuy = new LinkedHashMap<>();
        planned.forEach(a -> byBuy.computeIfAbsent(a.mediaBuyId(), k -> new ArrayList<>()).add(a));
        byBuy.forEach((buyId, list) -> mediaBuys.modify(buyId, current -> {
            boolean changed = false;
            List<MediaBuyPackage> pkgs = new ArrayList<>();
            for (MediaBuyPackage p : current.packages()) {
                Set<String> ids = new LinkedHashSet<>(p.creativeIds());
                list.stream().filter(a -> a.packageId().equals(p.packageId())).forEach(a -> ids.add(a.creativeId()));
                if (ids.size() != p.creativeIds().size()) {
                    changed = true;
                    pkgs.add(p.withCreativeIds(List.copyOf(ids)));
                } else {
                    pkgs.add(p);
                }
            }
            return changed ? current.withPackages(pkgs, mediaBuys.now()) : current;
        }));
        return new SyncResult(results, planned, byBuy.keySet());
    }

    private void validateCreative(CreativeInput c, String path, List<AdcpError> errors) {
        if (blank(c.name())) {
            errors.add(new AdcpError("INVALID_REQUEST", "name is required", path + ".name"));
        }
        Optional<CreativeFormat> format = c.formatId() == null ? Optional.empty() : catalog.format(c.formatId());
        if (format.isEmpty()) {
            errors.add(new AdcpError("UNKNOWN_FORMAT", "format_id " + c.formatId() + " is not offered; see list_creative_formats", path + ".format_id"));
        } else {
            switch (format.get().type()) {
                case "video" -> errors.add(new AdcpError("UNSUPPORTED_FORMAT",
                        "video creatives cannot be served by this ad server yet", path + ".format_id"));
                case "native" -> {
                    if (blank(c.html())) {
                        errors.add(new AdcpError("INVALID_REQUEST", "native creatives need html", path + ".html"));
                    }
                }
                default -> {
                    if (blank(c.imageUrl()) && blank(c.html())) {
                        errors.add(new AdcpError("INVALID_REQUEST", "display creatives need image_url or html", path + ".image_url"));
                    } else if (!blank(c.imageUrl()) && !isHttpUrl(c.imageUrl())) {
                        errors.add(new AdcpError("INVALID_REQUEST", "image_url must be http(s)", path + ".image_url"));
                    }
                }
            }
        }
        if (!isHttpUrl(c.clickUrl())) {
            errors.add(new AdcpError("INVALID_REQUEST", "click_url must be an http(s) URL", path + ".click_url"));
        }
    }

    static boolean isHttpUrl(String s) {
        if (blank(s)) {
            return false;
        }
        try {
            URI u = new URI(s);
            return ("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme()))
                    && u.getHost() != null && !u.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String emptyToNull(String s) {
        return blank(s) ? null : s;
    }
}
