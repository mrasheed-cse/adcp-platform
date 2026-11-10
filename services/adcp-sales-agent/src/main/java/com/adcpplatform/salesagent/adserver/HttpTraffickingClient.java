package com.adcpplatform.salesagent.adserver;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Calls the trafficking API (services/trafficking-api) over HTTP with snake_case JSON. */
@Component
public class HttpTraffickingClient implements TraffickingClient {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final HttpClient http;
    private final String base;
    private final Duration timeout;
    private final ObjectMapper json;

    public HttpTraffickingClient(TraffickingProperties props, ObjectMapper json) {
        this.base = props.baseUrl().replaceAll("/+$", "") + "/api/v1";
        this.timeout = Duration.ofMillis(props.timeoutMs());
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.json = json;
    }

    @Override
    public String ensureAdvertiser(String name, String externalRef) {
        return id(send("POST", "/advertisers", body("name", name, "external_ref", externalRef)));
    }

    @Override
    public void ensurePlacement(String placementId, String name) {
        try {
            send("POST", "/placements", body("id", placementId, "name", name));
        } catch (Conflict alreadyExists) {
            // created earlier: fine
        }
    }

    @Override
    public String ensureOrder(String advertiserId, String name, String externalRef) {
        return id(send("POST", "/orders", body("advertiser_id", advertiserId, "name", name, "external_ref", externalRef)));
    }

    @Override
    public void setOrderStatus(String orderId, String status) {
        send("PUT", "/orders/" + enc(orderId) + "/status", body("status", status));
    }

    @Override
    public String ensureLineItem(LineItemSpec s) {
        Map<String, Object> b = body("order_id", s.orderId(), "name", s.name(), "priority", s.priority(), "cpm", s.cpm(),
                "start", s.start().toString(), "end", s.end().toString(), "goal_impressions", s.goalImpressions(),
                "countries", s.countries(), "placements", s.placements(), "external_ref", s.externalRef());
        return id(send("POST", "/line-items", b));
    }

    @Override
    public void setLineItemStatus(String lineItemId, String status) {
        send("PUT", "/line-items/" + enc(lineItemId) + "/status", body("status", status));
    }

    @Override
    public String ensureCreative(CreativeSpec s) {
        Map<String, Object> b = body("advertiser_id", s.advertiserId(), "name", s.name(), "type", s.type(),
                "width", s.width(), "height", s.height(), "image_url", s.imageUrl(), "html", s.html(),
                "landing_url", s.landingUrl(), "external_ref", s.externalRef());
        return id(send("POST", "/creatives", b));
    }

    @Override
    public void attachCreative(String lineItemId, String creativeId) {
        send("PUT", "/line-items/" + enc(lineItemId) + "/creatives/" + enc(creativeId), null);
    }

    @Override
    public long delivered(String lineItemId) {
        Object d = send("GET", "/line-items/" + enc(lineItemId) + "/delivery", null).get("delivered");
        return d instanceof Number n ? n.longValue() : 0L;
    }

    // ---------------------------------------------------------------- HTTP

    /** 409 from the ad server: a duplicate or a race. Retryable, and expected for placements. */
    static final class Conflict extends TraffickingException {
        private static final long serialVersionUID = 1L;

        Conflict(String message) {
            super(message, false);
        }
    }

    private Map<String, Object> send(String method, String path, Map<String, Object> body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                .header("Accept", "application/json");
        try {
            if (body == null) {
                req.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                req.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                return resp.body() == null || resp.body().isBlank() ? Map.of() : json.readValue(resp.body(), MAP);
            }
            String detail = method + " " + path + " -> " + code + ": " + detail(resp.body());
            if (code == 409) {
                throw new Conflict(detail);
            }
            boolean permanent = code >= 400 && code < 500 && code != 408 && code != 429;
            throw new TraffickingException("ad server rejected " + detail, permanent);
        } catch (IOException e) {
            throw new TraffickingException("ad server unreachable: " + method + " " + path + ": " + e.getMessage(), false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TraffickingException("interrupted calling the ad server", false, e);
        }
    }

    private String detail(String body) {
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        try {
            Map<String, Object> m = json.readValue(body, MAP);
            Object errors = m.get("errors");
            return String.valueOf(m.getOrDefault("detail", "")) + (errors == null ? "" : " " + errors);
        } catch (JsonProcessingException e) {
            return body.length() > 300 ? body.substring(0, 300) : body;
        }
    }

    private static String id(Map<String, Object> resp) {
        Object id = resp.get("id");
        if (id == null) {
            throw new TraffickingException("ad server response has no id", false);
        }
        return id.toString();
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
