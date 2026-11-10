package com.adcpplatform.trafficking.rules;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.adcpplatform.trafficking.domain.LineItem;
import com.adcpplatform.trafficking.domain.LineItemFields;
import com.adcpplatform.trafficking.domain.LineItemStatus;

/** Line-item rules, mirroring contracts/line-item-snapshot.schema.json. Pure Java, no framework. */
public final class LineItemRules {

    public static final Set<Integer> PRIORITIES = Set.of(4, 8, 12, 16);
    public static final Set<String> DEVICES = Set.of("desktop", "mobile", "tablet");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Za-z]{2}$");
    private static final BigDecimal MAX_CPM = new BigDecimal("10000");

    private static final Map<LineItemStatus, Set<LineItemStatus>> TRANSITIONS = Map.of(
            LineItemStatus.DRAFT, Set.of(LineItemStatus.ACTIVE, LineItemStatus.ARCHIVED),
            LineItemStatus.ACTIVE, Set.of(LineItemStatus.PAUSED, LineItemStatus.ARCHIVED),
            LineItemStatus.PAUSED, Set.of(LineItemStatus.ACTIVE, LineItemStatus.ARCHIVED),
            LineItemStatus.ARCHIVED, Set.of());

    private LineItemRules() {}

    public static List<FieldError> validate(LineItemFields f) {
        List<FieldError> errors = new ArrayList<>();
        if (f.name() == null || f.name().isBlank()) {
            errors.add(new FieldError("name", "is required"));
        } else if (f.name().length() > 200) {
            errors.add(new FieldError("name", "must be at most 200 characters"));
        }
        if (!PRIORITIES.contains(f.priority())) {
            errors.add(new FieldError("priority", "must be 4 (sponsorship), 8 (standard), 12 (price priority) or 16 (house)"));
        }
        if (f.cpm() == null || f.cpm().signum() < 0 || f.cpm().compareTo(MAX_CPM) > 0) {
            errors.add(new FieldError("cpm", "must be between 0 and 10000"));
        }
        if (f.start() == null) {
            errors.add(new FieldError("start", "is required"));
        }
        if (f.end() == null) {
            errors.add(new FieldError("end", "is required"));
        }
        if (f.start() != null && f.end() != null && !f.end().isAfter(f.start())) {
            errors.add(new FieldError("end", "must be after start"));
        }
        if (f.goalImpressions() < 0) {
            errors.add(new FieldError("goal_impressions", "must be 0 (no goal) or positive"));
        }
        if ((f.freqMax() == null) != (f.freqWindowSeconds() == null)) {
            errors.add(new FieldError("frequency_cap", "needs both max and window_seconds"));
        } else if (f.freqMax() != null && (f.freqMax() < 1 || f.freqWindowSeconds() < 1)) {
            errors.add(new FieldError("frequency_cap", "max and window_seconds must be at least 1"));
        }
        for (String c : f.countries()) {
            if (c == null || !COUNTRY.matcher(c).matches()) {
                errors.add(new FieldError("countries", "'" + c + "' is not a two-letter country code"));
            }
        }
        for (String d : f.devices()) {
            if (!DEVICES.contains(d)) {
                errors.add(new FieldError("devices", "'" + d + "' must be desktop, mobile or tablet"));
            }
        }
        if (f.placements().isEmpty()) {
            errors.add(new FieldError("placements", "at least one placement is required"));
        }
        return errors;
    }

    public static boolean canTransition(LineItemStatus from, LineItemStatus to) {
        return TRANSITIONS.get(from).contains(to);
    }

    /** Extra checks before a line item may go live. */
    public static List<FieldError> readyToActivate(LineItem li, Instant now) {
        List<FieldError> errors = new ArrayList<>(validate(li.fields()));
        if (li.getCreatives().isEmpty()) {
            errors.add(new FieldError("creatives", "attach at least one creative before activating"));
        }
        if (li.getEnd() != null && !li.getEnd().isAfter(now)) {
            errors.add(new FieldError("end", "flight has already ended"));
        }
        return errors;
    }
}
