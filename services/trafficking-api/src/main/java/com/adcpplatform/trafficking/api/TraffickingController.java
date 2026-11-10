package com.adcpplatform.trafficking.api;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.adcpplatform.trafficking.api.Dtos.AdvertiserResponse;
import com.adcpplatform.trafficking.api.Dtos.CreativeRequest;
import com.adcpplatform.trafficking.api.Dtos.CreativeResponse;
import com.adcpplatform.trafficking.api.Dtos.DeliveryResponse;
import com.adcpplatform.trafficking.api.Dtos.LineItemRequest;
import com.adcpplatform.trafficking.api.Dtos.LineItemResponse;
import com.adcpplatform.trafficking.api.Dtos.NameRequest;
import com.adcpplatform.trafficking.api.Dtos.OrderRequest;
import com.adcpplatform.trafficking.api.Dtos.OrderResponse;
import com.adcpplatform.trafficking.api.Dtos.PlacementRequest;
import com.adcpplatform.trafficking.api.Dtos.PlacementResponse;
import com.adcpplatform.trafficking.api.Dtos.StatusRequest;
import com.adcpplatform.trafficking.domain.LineItem;
import com.adcpplatform.trafficking.domain.LineItemStatus;
import com.adcpplatform.trafficking.domain.OrderStatus;
import com.adcpplatform.trafficking.rules.ValidationException;
import com.adcpplatform.trafficking.service.TraffickingService;

@RestController
@RequestMapping("/api/v1")
public class TraffickingController {

    private final TraffickingService service;

    public TraffickingController(TraffickingService service) {
        this.service = service;
    }

    // ---- advertisers
    /** 201 when created; 200 with the existing advertiser when external_ref was already used. */
    @PostMapping("/advertisers")
    public ResponseEntity<AdvertiserResponse> createAdvertiser(@RequestBody NameRequest req) {
        return respond(service.createAdvertiser(req.name(), req.externalRef()), AdvertiserResponse::of);
    }

    @GetMapping("/advertisers")
    public List<AdvertiserResponse> advertisers() {
        return service.listAdvertisers().stream().map(AdvertiserResponse::of).toList();
    }

    // ---- orders
    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest req) {
        return respond(service.createOrder(req.advertiserId(), req.name(), req.externalRef()), OrderResponse::of);
    }

    @GetMapping("/orders")
    public List<OrderResponse> orders(@RequestParam(name = "advertiser_id", required = false) String advertiserId) {
        return service.listOrders(advertiserId).stream().map(OrderResponse::of).toList();
    }

    @PutMapping("/orders/{id}/status")
    public OrderResponse orderStatus(@PathVariable("id") String id, @RequestBody StatusRequest req) {
        return OrderResponse.of(service.setOrderStatus(id, parse(OrderStatus.class, req.status())));
    }

    // ---- placements
    @PostMapping("/placements")
    @ResponseStatus(HttpStatus.CREATED)
    public PlacementResponse createPlacement(@RequestBody PlacementRequest req) {
        return PlacementResponse.of(service.createPlacement(req.id(), req.name()));
    }

    @GetMapping("/placements")
    public List<PlacementResponse> placements() {
        return service.listPlacements().stream().map(PlacementResponse::of).toList();
    }

    // ---- creatives
    @PostMapping("/creatives")
    public ResponseEntity<CreativeResponse> createCreative(@RequestBody CreativeRequest req) {
        return respond(service.createCreative(req.advertiserId(), req.name(), req.parsedType(),
                req.width() == null ? 0 : req.width(), req.height() == null ? 0 : req.height(),
                req.imageUrl(), req.html(), req.landingUrl(), req.externalRef()), CreativeResponse::of);
    }

    @GetMapping("/creatives")
    public List<CreativeResponse> creatives(@RequestParam(name = "advertiser_id", required = false) String advertiserId) {
        return service.listCreatives(advertiserId).stream().map(CreativeResponse::of).toList();
    }

    // ---- line items
    @PostMapping("/line-items")
    public ResponseEntity<LineItemResponse> createLineItem(@RequestBody LineItemRequest req) {
        return respond(service.createLineItem(req.orderId(), req.fields(), req.externalRef()), LineItemResponse::of);
    }

    @GetMapping("/line-items")
    public List<LineItemResponse> lineItems() {
        return service.listLineItems().stream().map(LineItemResponse::of).toList();
    }

    @GetMapping("/line-items/{id}")
    public LineItemResponse lineItem(@PathVariable("id") String id) {
        return LineItemResponse.of(service.getLineItem(id));
    }

    @PutMapping("/line-items/{id}")
    public LineItemResponse updateLineItem(@PathVariable("id") String id, @RequestBody LineItemRequest req) {
        return LineItemResponse.of(service.updateLineItem(id, req.fields(), req.version()));
    }

    /** Body: {"status": "active" | "paused" | "archived", "version": 3} */
    @PutMapping("/line-items/{id}/status")
    public LineItemResponse lineItemStatus(@PathVariable("id") String id, @RequestBody StatusRequest req) {
        LineItemStatus target = parse(LineItemStatus.class, req.status());
        return LineItemResponse.of(service.transition(id, target, req.version()));
    }

    @PutMapping("/line-items/{id}/creatives/{creativeId}")
    public LineItemResponse attach(@PathVariable("id") String id, @PathVariable("creativeId") String creativeId) {
        return LineItemResponse.of(service.attachCreative(id, creativeId));
    }

    @DeleteMapping("/line-items/{id}/creatives/{creativeId}")
    public LineItemResponse detach(@PathVariable("id") String id, @PathVariable("creativeId") String creativeId) {
        return LineItemResponse.of(service.detachCreative(id, creativeId));
    }

    @GetMapping("/line-items/{id}/delivery")
    public DeliveryResponse delivery(@PathVariable("id") String id) {
        LineItem li = service.getLineItem(id);
        long delivered = service.delivered(id);
        Double progress = li.getGoalImpressions() > 0 ? Math.min(1.0, (double) delivered / li.getGoalImpressions()) : null;
        return new DeliveryResponse(id, li.getGoalImpressions(), delivered, progress);
    }

    private static <T, R> ResponseEntity<R> respond(TraffickingService.Upserted<T> u, Function<T, R> mapper) {
        return ResponseEntity.status(u.created() ? HttpStatus.CREATED : HttpStatus.OK).body(mapper.apply(u.value()));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null) {
            throw new ValidationException("status", "is required");
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("status", "unknown status '" + value + "'");
        }
    }
}
