package com.adcpplatform.trafficking.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.adcpplatform.trafficking.TestData;
import com.adcpplatform.trafficking.domain.LineItem;
import com.adcpplatform.trafficking.domain.LineItemFields;
import com.adcpplatform.trafficking.domain.LineItemStatus;

class LineItemRulesTest {

    private static Set<String> fieldsWithErrors(LineItemFields f) {
        return LineItemRules.validate(f).stream().map(FieldError::field).collect(Collectors.toSet());
    }

    @Test
    void validLineItemHasNoErrors() {
        assertEquals(List.of(), LineItemRules.validate(TestData.fields()));
    }

    @Test
    void reportsEveryInvalidField() {
        LineItemFields bad = new LineItemFields(" ", 5, new BigDecimal("-1"),
                Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), -1,
                5, null, List.of("BGD"), List.of("watch"), List.of());
        assertEquals(Set.of("name", "priority", "cpm", "end", "goal_impressions", "frequency_cap", "countries",
                "devices", "placements"), fieldsWithErrors(bad));
    }

    @Test
    void missingDatesAndZeroFrequencyAreRejected() {
        LineItemFields f = new LineItemFields("x", 8, BigDecimal.ONE, null, null, 0, 0, 0, null, null, List.of("p"));
        assertEquals(Set.of("start", "end", "frequency_cap"), fieldsWithErrors(f));
    }

    @Test
    void statusTransitions() {
        assertTrue(LineItemRules.canTransition(LineItemStatus.DRAFT, LineItemStatus.ACTIVE));
        assertTrue(LineItemRules.canTransition(LineItemStatus.ACTIVE, LineItemStatus.PAUSED));
        assertTrue(LineItemRules.canTransition(LineItemStatus.PAUSED, LineItemStatus.ACTIVE));
        assertTrue(LineItemRules.canTransition(LineItemStatus.PAUSED, LineItemStatus.ARCHIVED));
        assertFalse(LineItemRules.canTransition(LineItemStatus.DRAFT, LineItemStatus.PAUSED));
        assertFalse(LineItemRules.canTransition(LineItemStatus.ARCHIVED, LineItemStatus.ACTIVE));
    }

    @Test
    void activationNeedsCreativeAndFutureEnd() {
        LineItem ok = TestData.active("li_ok");
        assertEquals(List.of(), LineItemRules.readyToActivate(ok, TestData.NOW));

        LineItem noCreative = TestData.active("li_nc");
        noCreative.getCreatives().clear();
        assertEquals(Set.of("creatives"), LineItemRules.readyToActivate(noCreative, TestData.NOW).stream()
                .map(FieldError::field).collect(Collectors.toSet()));

        assertEquals(Set.of("end"), LineItemRules.readyToActivate(ok, Instant.parse("2026-12-01T00:00:00Z")).stream()
                .map(FieldError::field).collect(Collectors.toSet()));
    }
}
