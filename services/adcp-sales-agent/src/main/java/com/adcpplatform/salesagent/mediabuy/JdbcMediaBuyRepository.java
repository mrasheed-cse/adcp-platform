package com.adcpplatform.salesagent.mediabuy;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.adcpplatform.salesagent.model.Models.MediaBuy;
import com.adcpplatform.salesagent.model.Models.MediaBuyPackage;
import com.adcpplatform.salesagent.model.Models.MediaBuyStatus;
import com.adcpplatform.salesagent.model.Models.Provisioning;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Postgres storage for media buys (schema sales_agent, see db/migration). */
@Repository
public class JdbcMediaBuyRepository implements MediaBuyRepository {

    private static final String COLUMNS = """
            id, account_id, idempotency_key, request_hash, brand, status, start_time, end_time, total_budget,
            currency, packages, provisioning, provisioning_error, dirty, trafficking_advertiser_id,
            trafficking_order_id, version, created_at, updated_at""";

    /** Storage shape of a package: includes the internal fields the API hides. */
    record PackageRow(String packageId, String productId, String pricingOptionId, BigDecimal budget, String buyerRef,
                      List<String> creativeIds, BigDecimal rate, String lineItemId) {}

    private static final TypeReference<List<PackageRow>> PACKAGE_ROWS = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcMediaBuyRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<MediaBuy> findById(String mediaBuyId) {
        return first(jdbc.query("SELECT " + COLUMNS + " FROM media_buy WHERE id = :id", Map.of("id", mediaBuyId), this::row));
    }

    @Override
    public Optional<MediaBuy> findByIdempotencyKey(String accountId, String idempotencyKey) {
        return first(jdbc.query("SELECT " + COLUMNS + " FROM media_buy WHERE account_id = :a AND idempotency_key = :k",
                Map.of("a", accountId, "k", idempotencyKey), this::row));
    }

    @Override
    public MediaBuy saveIfKeyAbsent(MediaBuy buy) {
        int inserted = jdbc.update("INSERT INTO media_buy (" + COLUMNS + ") VALUES (:id, :account_id, :idempotency_key, "
                + ":request_hash, :brand, :status, :start_time, :end_time, :total_budget, :currency, CAST(:packages AS jsonb), "
                + ":provisioning, :provisioning_error, :dirty, :trafficking_advertiser_id, :trafficking_order_id, :version, "
                + ":created_at, :updated_at) ON CONFLICT (account_id, idempotency_key) DO NOTHING", params(buy));
        if (inserted == 1) {
            return buy;
        }
        return findByIdempotencyKey(buy.accountId(), buy.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("conflicting media buy vanished"));
    }

    @Override
    public boolean updateIfVersion(MediaBuy b, long expectedVersion) {
        Map<String, Object> p = params(b);
        p.put("expected", expectedVersion);
        return jdbc.update("""
                UPDATE media_buy SET status = :status, packages = CAST(:packages AS jsonb), provisioning = :provisioning,
                       provisioning_error = :provisioning_error, dirty = :dirty,
                       trafficking_advertiser_id = :trafficking_advertiser_id, trafficking_order_id = :trafficking_order_id,
                       version = :version, updated_at = :updated_at
                 WHERE id = :id AND version = :expected""", p) == 1;
    }

    @Override
    public Collection<MediaBuy> findByAccount(String accountId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM media_buy WHERE account_id = :a ORDER BY created_at",
                Map.of("a", accountId), this::row);
    }

    @Override
    public Collection<MediaBuy> findOpen() {
        return jdbc.query("SELECT " + COLUMNS + " FROM media_buy WHERE status IN ('PENDING_ACTIVATION', 'ACTIVE', 'PAUSED')",
                Map.of(), this::row);
    }

    @Override
    public List<MediaBuy> findDirty(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM media_buy WHERE dirty ORDER BY updated_at LIMIT :limit",
                Map.of("limit", limit), this::row);
    }

    private Map<String, Object> params(MediaBuy b) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", b.mediaBuyId());
        p.put("account_id", b.accountId());
        p.put("idempotency_key", b.idempotencyKey());
        p.put("request_hash", b.requestHash());
        p.put("brand", b.brand());
        p.put("status", b.status().name());
        p.put("start_time", ts(b.startTime()));
        p.put("end_time", ts(b.endTime()));
        p.put("total_budget", b.totalBudget());
        p.put("currency", b.currency());
        p.put("packages", toJson(b.packages()));
        p.put("provisioning", b.provisioning().name());
        p.put("provisioning_error", b.provisioningError());
        p.put("dirty", b.dirty());
        p.put("trafficking_advertiser_id", b.traffickingAdvertiserId());
        p.put("trafficking_order_id", b.traffickingOrderId());
        p.put("version", b.version());
        p.put("created_at", ts(b.createdAt()));
        p.put("updated_at", ts(b.updatedAt()));
        return p;
    }

    private MediaBuy row(ResultSet rs, int n) throws SQLException {
        return new MediaBuy(rs.getString("id"), rs.getString("brand"), MediaBuyStatus.valueOf(rs.getString("status")),
                instant(rs, "start_time"), instant(rs, "end_time"), rs.getBigDecimal("total_budget"),
                rs.getString("currency"), fromJson(rs.getString("packages")), instant(rs, "created_at"),
                instant(rs, "updated_at"), Provisioning.valueOf(rs.getString("provisioning")),
                rs.getString("provisioning_error"), rs.getString("account_id"), rs.getString("idempotency_key"),
                rs.getString("request_hash"), rs.getLong("version"), rs.getBoolean("dirty"),
                rs.getString("trafficking_advertiser_id"), rs.getString("trafficking_order_id"));
    }

    private String toJson(List<MediaBuyPackage> packages) {
        List<PackageRow> rows = packages.stream().map(p -> new PackageRow(p.packageId(), p.productId(),
                p.pricingOptionId(), p.budget(), p.buyerRef(), p.creativeIds(), p.rate(), p.lineItemId())).toList();
        try {
            return json.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize packages", e);
        }
    }

    private List<MediaBuyPackage> fromJson(String raw) {
        try {
            return json.readValue(raw, PACKAGE_ROWS).stream().map(r -> new MediaBuyPackage(r.packageId(), r.productId(),
                    r.pricingOptionId(), r.budget(), r.buyerRef(), r.creativeIds(), r.rate(), r.lineItemId())).toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read packages", e);
        }
    }

    static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
}
