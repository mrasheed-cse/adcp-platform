package com.adcpplatform.trafficking.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.adcpplatform.trafficking.domain.CreativeType;

class CreativeRulesTest {

    private static Set<String> errors(String name, CreativeType type, int w, int h, String img, String html, String lp) {
        return CreativeRules.validate(name, type, w, h, img, html, lp).stream().map(FieldError::field).collect(Collectors.toSet());
    }

    @Test
    void validCreatives() {
        assertEquals(List.of(), CreativeRules.validate("Banner", CreativeType.IMAGE, 300, 250,
                "https://cdn.test/a.png", null, "https://shop.test/"));
        assertEquals(List.of(), CreativeRules.validate("House", CreativeType.HTML, 300, 250,
                null, "<b>hi</b>", "http://pub.test/sub"));
    }

    @Test
    void imageNeedsImageUrlAndHtmlNeedsHtml() {
        assertEquals(Set.of("image_url"), errors("a", CreativeType.IMAGE, 300, 250, null, "<b/>", "https://x.test/"));
        assertEquals(Set.of("html"), errors("a", CreativeType.HTML, 300, 250, "https://x.test/a.png", " ", "https://x.test/"));
    }

    @Test
    void rejectsUnsafeLandingPagesAndBadSizes() {
        for (String lp : new String[] {"javascript:alert(1)", "ftp://x.test/", "https://", "not a url", null}) {
            assertEquals(Set.of("landing_url"), errors("a", CreativeType.HTML, 300, 250, null, "<b/>", lp), lp);
        }
        assertEquals(Set.of("width"), errors("a", CreativeType.HTML, 0, 250, null, "<b/>", "https://x.test/"));
        assertEquals(Set.of("name", "type", "landing_url"), errors("", null, 300, 250, null, null, ""));
    }
}
