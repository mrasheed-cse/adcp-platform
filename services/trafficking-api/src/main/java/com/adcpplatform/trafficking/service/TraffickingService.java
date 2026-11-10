package com.adcpplatform.trafficking.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.adcpplatform.trafficking.domain.AdOrder;
import com.adcpplatform.trafficking.domain.Advertiser;
import com.adcpplatform.trafficking.domain.Creative;
import com.adcpplatform.trafficking.domain.CreativeType;
import com.adcpplatform.trafficking.domain.Ids;
import com.adcpplatform.trafficking.domain.LineItem;
import com.adcpplatform.trafficking.domain.LineItemFields;
import com.adcpplatform.trafficking.domain.LineItemStatus;
import com.adcpplatform.trafficking.domain.OrderStatus;
import com.adcpplatform.trafficking.domain.Placement;
import com.adcpplatform.trafficking.rules.CreativeRules;
import com.adcpplatform.trafficking.rules.FieldError;
import com.adcpplatform.trafficking.rules.LineItemRules;
import com.adcpplatform.trafficking.rules.ValidationException;
import com.adcpplatform.trafficking.snapshot.SnapshotStore;
import com.adcpplatform.trafficking.snapshot.TraffickingChanged;

/** All writes go through here: validated, transactional, and followed by a snapshot republish after commit. */
@Service
@Transactional
public class TraffickingService {

    private final AdvertiserRepository advertisers;
    private final OrderRepository orders;
    private final PlacementRepository placements;
    private final CreativeRepository creatives;
    private final LineItemRepository lineItems;
    private final SnapshotStore snapshotStore;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public TraffickingService(AdvertiserRepository advertisers, OrderRepository orders, PlacementRepository placements,
                              CreativeRepository creatives, LineItemRepository lineItems, SnapshotStore snapshotStore,
                              ApplicationEventPublisher events, Clock clock) {
        this.advertisers = advertisers;
        this.orders = orders;
        this.placements = placements;
        this.creatives = creatives;
        this.lineItems = lineItems;
        this.snapshotStore = snapshotStore;
        this.events = events;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- advertisers, orders, placements

    /** Result of an idempotent create: the existing row is returned when externalRef was already used. */
    public record Upserted<T>(T value, boolean created) {}

    public Upserted<Advertiser> createAdvertiser(String name, String externalRef) {
        requireName(name);
        if (externalRef != null) {
            var existing = advertisers.findByExternalRef(externalRef);
            if (existing.isPresent()) {
                return new Upserted<>(existing.get(), false);
            }
        }
        Advertiser a = new Advertiser(Ids.next("adv"), name.trim());
        a.setExternalRef(externalRef);
        return new Upserted<>(advertisers.save(a), true);
    }

    @Transactional(readOnly = true)
    public List<Advertiser> listAdvertisers() {
        return advertisers.findAll();
    }

    public Upserted<AdOrder> createOrder(String advertiserId, String name, String externalRef) {
        requireName(name);
        advertiser(advertiserId);
        if (externalRef != null) {
            var existing = orders.findByExternalRef(externalRef);
            if (existing.isPresent()) {
                sameParent("order", existing.get().getAdvertiserId(), advertiserId, externalRef);
                return new Upserted<>(existing.get(), false);
            }
        }
        AdOrder o = new AdOrder(Ids.next("ord"), advertiserId, name.trim());
        o.setExternalRef(externalRef);
        return new Upserted<>(orders.save(o), true);
    }

    @Transactional(readOnly = true)
    public List<AdOrder> listOrders(String advertiserId) {
        return advertiserId == null ? orders.findAll() : orders.findByAdvertiserId(advertiserId);
    }

    public AdOrder setOrderStatus(String orderId, OrderStatus status) {
        AdOrder o = order(orderId);
        if (o.getStatus() == OrderStatus.ARCHIVED && status != OrderStatus.ARCHIVED) {
            throw new ConflictException("order " + orderId + " is archived");
        }
        o.setStatus(status);
        changed("order status");
        return o;
    }

    public Placement createPlacement(String id, String name) {
        if (id == null || !id.matches("^[a-z0-9_]{2,64}$")) {
            throw new ValidationException("id", "use 2-64 lower-case letters, digits or underscores, e.g. sports_top");
        }
        requireName(name);
        if (placements.existsById(id)) {
            throw new ConflictException("placement " + id + " already exists");
        }
        return placements.save(new Placement(id, name.trim()));
    }

    @Transactional(readOnly = true)
    public List<Placement> listPlacements() {
        return placements.findAll();
    }

    // ---------------------------------------------------------------- creatives

    public Upserted<Creative> createCreative(String advertiserId, String name, CreativeType type, int width, int height,
                                             String imageUrl, String html, String landingUrl, String externalRef) {
        ValidationException.throwIfAny(CreativeRules.validate(name, type, width, height, imageUrl, html, landingUrl));
        advertiser(advertiserId);
        if (externalRef != null) {
            var existing = creatives.findByExternalRef(externalRef);
            if (existing.isPresent()) {
                sameParent("creative", existing.get().getAdvertiserId(), advertiserId, externalRef);
                return new Upserted<>(existing.get(), false);
            }
        }
        Creative c = new Creative(Ids.next("cr"), advertiserId, name.trim(), type, width, height,
                type == CreativeType.IMAGE ? imageUrl : null, type == CreativeType.HTML ? html : null, landingUrl);
        c.setExternalRef(externalRef);
        return new Upserted<>(creatives.save(c), true);
    }

    @Transactional(readOnly = true)
    public List<Creative> listCreatives(String advertiserId) {
        return advertiserId == null ? creatives.findAll() : creatives.findByAdvertiserId(advertiserId);
    }

    // ---------------------------------------------------------------- line items

    public Upserted<LineItem> createLineItem(String orderId, LineItemFields fields, String externalRef) {
        AdOrder o = order(orderId);
        if (externalRef != null) {
            var existing = lineItems.findDetailedByExternalRef(externalRef);
            if (existing.isPresent()) {
                sameParent("line item", existing.get().getOrder().getId(), orderId, externalRef);
                return new Upserted<>(existing.get(), false);
            }
        }
        if (o.getStatus() == OrderStatus.ARCHIVED) {
            throw new ConflictException("order " + orderId + " is archived");
        }
        validate(fields);
        LineItem li = new LineItem(Ids.next("li"), o);
        li.apply(fields, clock.instant());
        li.setExternalRef(externalRef);
        return new Upserted<>(lineItems.save(li), true); // a new draft is not servable, so no republish needed
    }

    /** expectedVersion guards against overwriting someone else's edit (optimistic locking). */
    public LineItem updateLineItem(String id, LineItemFields fields, Long expectedVersion) {
        LineItem li = lineItem(id);
        checkVersion(li, expectedVersion);
        if (li.getStatus() == LineItemStatus.ARCHIVED) {
            throw new ConflictException("line item " + id + " is archived");
        }
        validate(fields);
        li.apply(fields, clock.instant());
        changed("line item updated");
        return li;
    }

    public LineItem transition(String id, LineItemStatus target, Long expectedVersion) {
        LineItem li = lineItem(id);
        checkVersion(li, expectedVersion);
        if (li.getStatus() == target) {
            return li;
        }
        if (!LineItemRules.canTransition(li.getStatus(), target)) {
            throw new ConflictException("cannot move line item from " + li.getStatus() + " to " + target);
        }
        if (target == LineItemStatus.ACTIVE) {
            ValidationException.throwIfAny(LineItemRules.readyToActivate(li, clock.instant()));
        }
        li.setStatus(target, clock.instant());
        changed("line item " + target);
        return li;
    }

    public LineItem attachCreative(String lineItemId, String creativeId) {
        LineItem li = lineItem(lineItemId);
        Creative c = creatives.findById(creativeId).orElseThrow(() -> new NotFoundException("creative", creativeId));
        if (!c.getAdvertiserId().equals(li.getOrder().getAdvertiserId())) {
            throw new ValidationException("creative_id", "creative belongs to a different advertiser");
        }
        if (li.getCreatives().add(c)) {
            changed("creative attached");
        }
        return li;
    }

    public LineItem detachCreative(String lineItemId, String creativeId) {
        LineItem li = lineItem(lineItemId);
        boolean attached = li.getCreatives().stream().anyMatch(c -> c.getId().equals(creativeId));
        if (attached && li.getStatus() == LineItemStatus.ACTIVE && li.getCreatives().size() == 1) {
            throw new ValidationException("creative_id", "an active line item needs at least one creative; pause it first");
        }
        if (li.getCreatives().removeIf(c -> c.getId().equals(creativeId))) {
            changed("creative detached");
        }
        return li;
    }

    @Transactional(readOnly = true)
    public LineItem getLineItem(String id) {
        return lineItem(id);
    }

    @Transactional(readOnly = true)
    public List<LineItem> listLineItems() {
        return lineItems.findAllDetailed();
    }

    @Transactional(readOnly = true)
    public long delivered(String lineItemId) {
        lineItem(lineItemId);
        Map<String, Long> d = snapshotStore.delivered();
        return d.getOrDefault(lineItemId, 0L);
    }

    // ---------------------------------------------------------------- helpers

    private void validate(LineItemFields fields) {
        List<FieldError> errors = new ArrayList<>(LineItemRules.validate(fields));
        Set<String> missing = new HashSet<>(fields.placements());
        placements.findAllById(fields.placements()).forEach(p -> missing.remove(p.getId()));
        if (!missing.isEmpty()) {
            errors.add(new FieldError("placements", "unknown placements: " + String.join(", ", missing.stream().sorted().toList())));
        }
        ValidationException.throwIfAny(errors);
    }

    private void checkVersion(LineItem li, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != li.getVersion()) {
            throw new ConflictException("line item " + li.getId() + " was changed by someone else (version "
                    + li.getVersion() + ", you sent " + expectedVersion + "); reload and retry");
        }
    }

    private static void sameParent(String what, String actualParent, String requestedParent, String externalRef) {
        if (!actualParent.equals(requestedParent)) {
            throw new ConflictException(what + " with external_ref " + externalRef + " already exists under " + actualParent);
        }
    }

    private void changed(String reason) {
        events.publishEvent(new TraffickingChanged(reason));
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 200) {
            throw new ValidationException("name", "is required (at most 200 characters)");
        }
    }

    private Advertiser advertiser(String id) {
        return advertisers.findById(id).orElseThrow(() -> new NotFoundException("advertiser", id));
    }

    private AdOrder order(String id) {
        return orders.findById(id).orElseThrow(() -> new NotFoundException("order", id));
    }

    private LineItem lineItem(String id) {
        return lineItems.findDetailed(id).orElseThrow(() -> new NotFoundException("line item", id));
    }
}
