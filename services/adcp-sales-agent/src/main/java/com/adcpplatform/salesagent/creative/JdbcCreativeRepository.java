package com.adcpplatform.salesagent.creative;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.adcpplatform.salesagent.model.Models.SyncedCreative;

@Repository
public class JdbcCreativeRepository implements CreativeRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCreativeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SyncedCreative> find(String accountId, String creativeId) {
        List<SyncedCreative> rows = jdbc.query("SELECT * FROM creative WHERE account_id = :a AND creative_id = :c",
                Map.of("a", accountId, "c", creativeId), JdbcCreativeRepository::row);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public boolean insertIfAbsent(SyncedCreative c) {
        Map<String, Object> p = new HashMap<>();
        p.put("a", c.accountId());
        p.put("c", c.creativeId());
        p.put("name", c.name());
        p.put("format", c.formatId());
        p.put("image", c.imageUrl());
        p.put("html", c.html());
        p.put("click", c.clickUrl());
        p.put("tid", c.traffickingCreativeId());
        p.put("created", c.createdAt().atOffset(ZoneOffset.UTC));
        return jdbc.update("""
                INSERT INTO creative (account_id, creative_id, name, format_id, image_url, html, click_url,
                                      trafficking_creative_id, created_at)
                VALUES (:a, :c, :name, :format, :image, :html, :click, :tid, :created)
                ON CONFLICT (account_id, creative_id) DO NOTHING""", p) == 1;
    }

    @Override
    public void setTraffickingId(String accountId, String creativeId, String id) {
        jdbc.update("UPDATE creative SET trafficking_creative_id = :t WHERE account_id = :a AND creative_id = :c",
                Map.of("t", id, "a", accountId, "c", creativeId));
    }

    private static SyncedCreative row(ResultSet rs, int n) throws SQLException {
        return new SyncedCreative(rs.getString("account_id"), rs.getString("creative_id"), rs.getString("name"),
                rs.getString("format_id"), rs.getString("image_url"), rs.getString("html"), rs.getString("click_url"),
                rs.getString("trafficking_creative_id"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
