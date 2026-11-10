package com.adcpplatform.salesagent.creative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.adcpplatform.salesagent.Fixture;
import com.adcpplatform.salesagent.mediabuy.AdcpException;
import com.adcpplatform.salesagent.model.Models.AdcpError;
import com.adcpplatform.salesagent.model.Models.CreativeInput;
import com.adcpplatform.salesagent.model.Models.MediaBuy;

class CreativeServiceTest {

    private final Fixture f = new Fixture();

    private static CreativeInput banner(String id) {
        return new CreativeInput(id, "Banner", "display_300x250", "https://cdn.acme.test/b.png", null, "https://acme.test/");
    }

    private Set<String> codes(Runnable r) {
        AdcpException e = assertThrows(AdcpException.class, r::run);
        return e.errors().stream().map(AdcpError::code).collect(Collectors.toSet());
    }

    @Test
    void assignsCreativesToPackagesAndMarksTheBuyForSync() {
        MediaBuy buy = f.reconciler.reconcile(f.book("acme-key-0000000001").mediaBuyId());
        String news = buy.packages().get(0).packageId();
        String edu = buy.packages().get(1).packageId();

        var r = f.creativeService.sync(Fixture.ACCOUNT, List.of(banner("b1"),
                new CreativeInput("n1", "Native", "native_article", null, "<div>Learn</div>", "https://acme.test/learn")),
                Map.of("b1", List.of(news, edu), "n1", List.of(edu)));

        assertEquals(Set.of(buy.mediaBuyId()), r.mediaBuyIds());
        MediaBuy after = f.service.get(Fixture.ACCOUNT, buy.mediaBuyId());
        assertTrue(after.dirty());
        assertEquals(List.of("b1"), after.packages().get(0).creativeIds());
        assertEquals(Set.of("b1", "n1"), Set.copyOf(after.packages().get(1).creativeIds()));

        // Same content again: unchanged, no new version.
        long v = after.version();
        var again = f.creativeService.sync(Fixture.ACCOUNT, List.of(banner("b1")), Map.of("b1", List.of(news)));
        assertEquals("unchanged", again.creatives().get(0).action());
        assertEquals(v, f.service.get(Fixture.ACCOUNT, buy.mediaBuyId()).version());
    }

    @Test
    void rejectsBadCreativesWithoutWritingAnything() {
        f.book("acme-key-0000000002");
        Set<String> c = codes(() -> f.creativeService.sync(Fixture.ACCOUNT, List.of(
                new CreativeInput("v1", "Video", "video_15s", null, "<x/>", "https://acme.test/"),
                new CreativeInput("x1", "Bad", "display_999x999", "https://cdn.test/a.png", null, "https://acme.test/"),
                new CreativeInput("c1", "No click", "display_300x250", "https://cdn.test/a.png", null, "javascript:alert(1)"),
                new CreativeInput("n1", "Native without html", "native_article", "https://cdn.test/a.png", null, "https://acme.test/")),
                Map.of()));
        assertEquals(Set.of("UNSUPPORTED_FORMAT", "UNKNOWN_FORMAT", "INVALID_REQUEST"), c);
        assertTrue(f.creatives.find(Fixture.ACCOUNT, "v1").isEmpty());
    }

    @Test
    void rejectsBadAssignments() {
        MediaBuy buy = f.book("acme-key-0000000003");
        String news = buy.packages().get(0).packageId();
        // A native creative cannot run on the display-only news package; unknown packages and creatives are reported.
        Set<String> c = codes(() -> f.creativeService.sync(Fixture.ACCOUNT, List.of(
                new CreativeInput("n1", "Native", "native_article", null, "<div/>", "https://acme.test/")),
                Map.of("n1", List.of(news, "pkg_nope"), "ghost", List.of(news))));
        assertEquals(Set.of("FORMAT_NOT_ACCEPTED", "PACKAGE_NOT_FOUND", "CREATIVE_NOT_FOUND"), c);
        assertTrue(f.creatives.find(Fixture.ACCOUNT, "n1").isEmpty());
    }

    @Test
    void creativesAreImmutableAndScopedToTheAccount() {
        MediaBuy buy = f.book("acme-key-0000000004");
        f.creativeService.sync(Fixture.ACCOUNT, List.of(banner("b1")), Map.of());
        CreativeInput changed = new CreativeInput("b1", "Banner", "display_300x250", "https://cdn.acme.test/NEW.png", null,
                "https://acme.test/");
        assertEquals(Set.of("CREATIVE_IMMUTABLE"), codes(() -> f.creativeService.sync(Fixture.ACCOUNT, List.of(changed), Map.of())));

        // Another account cannot assign creatives to this account's packages.
        String news = buy.packages().get(0).packageId();
        assertEquals(Set.of("PACKAGE_NOT_FOUND"), codes(() -> f.creativeService.sync("acct-other", List.of(banner("b1")),
                Map.of("b1", List.of(news)))));
    }

    @Test
    void canceledBuysTakeNoCreatives() {
        MediaBuy buy = f.book("acme-key-0000000005");
        f.service.cancel(Fixture.ACCOUNT, buy.mediaBuyId());
        assertEquals(Set.of("INVALID_STATE"), codes(() -> f.creativeService.sync(Fixture.ACCOUNT, List.of(banner("b1")),
                Map.of("b1", List.of(buy.packages().get(0).packageId())))));
    }
}
