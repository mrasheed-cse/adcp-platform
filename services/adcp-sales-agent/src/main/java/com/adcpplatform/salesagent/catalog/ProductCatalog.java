package com.adcpplatform.salesagent.catalog;

import com.adcpplatform.salesagent.model.Models.CreativeFormat;
import com.adcpplatform.salesagent.model.Models.DeliveryType;
import com.adcpplatform.salesagent.model.Models.PricingOption;
import com.adcpplatform.salesagent.model.Models.Product;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mock inventory for a fictional Bangladeshi publisher network.
 * Replace with a DB-backed catalog or an ad-server (e.g. GAM) integration later.
 */
@Component
public class ProductCatalog {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "for", "to", "of", "in", "on", "with", "ads", "ad",
            "people", "who", "are", "is", "my", "our", "want", "reach", "interested");

    private final Map<String, Product> products;
    private final List<CreativeFormat> formats = List.of(
            new CreativeFormat("display_300x250", "Medium Rectangle", "display", 300, 250, null),
            new CreativeFormat("display_728x90", "Leaderboard", "display", 728, 90, null),
            new CreativeFormat("display_320x50", "Mobile Banner", "display", 320, 50, null),
            new CreativeFormat("video_15s", "Video 15s", "video", 1920, 1080, 15),
            new CreativeFormat("video_30s", "Video 30s", "video", 1920, 1080, 30),
            new CreativeFormat("native_article", "Native In-Feed", "native", null, null, null));

    public ProductCatalog() {
        List<Product> seed = List.of(
                new Product("bd_sports_ctv",
                        "Cricket & Football CTV",
                        "Pre-roll and mid-roll video on live and highlight cricket/football streams in Bangladesh.",
                        List.of("video_15s", "video_30s"),
                        DeliveryType.GUARANTEED,
                        List.of(cpm("cpm_usd_fixed", "12.00", true, "1000")),
                        List.of("BD"), 400_000,
                        List.of("sports", "cricket", "football", "ctv", "video", "tv", "streaming", "live"),
                        List.of("sports_video_preroll")),
                new Product("bd_news_display",
                        "Bangla News Display",
                        "Run-of-site display across top Bangla-language news portals.",
                        List.of("display_300x250", "display_728x90", "display_320x50"),
                        DeliveryType.NON_GUARANTEED,
                        List.of(cpm("cpm_usd_auction", "1.20", false, "200")),
                        List.of("BD"), 3_000_000,
                        List.of("news", "display", "bangla", "banner", "politics", "current", "affairs"),
                        List.of("news_top", "news_sidebar")),
                new Product("bd_mobile_gaming",
                        "Mobile Gaming Rewarded & Banner",
                        "In-app banner and rewarded placements in casual mobile games popular with 16-30 year-olds.",
                        List.of("display_320x50", "video_15s"),
                        DeliveryType.NON_GUARANTEED,
                        List.of(cpm("cpm_usd_auction", "0.90", false, "150")),
                        List.of("BD"), 2_200_000,
                        List.of("gaming", "games", "mobile", "app", "youth", "young", "gen", "rewarded"),
                        List.of("games_banner")),
                new Product("bd_education_native",
                        "Education & Job-Prep Native",
                        "Native in-feed units on exam-prep and career sites (BCS, bank jobs, admissions).",
                        List.of("native_article", "display_300x250"),
                        DeliveryType.GUARANTEED,
                        List.of(cpm("cpm_usd_fixed", "3.50", true, "300")),
                        List.of("BD"), 350_000,
                        List.of("education", "students", "exam", "jobs", "career", "bcs", "learning", "native"),
                        List.of("jobs_feed")),
                new Product("bd_ecommerce_display",
                        "E-commerce Shopper Display",
                        "Display on shopping, deals, and product-review pages; strong for retail and fintech offers.",
                        List.of("display_300x250", "display_320x50"),
                        DeliveryType.NON_GUARANTEED,
                        List.of(cpm("cpm_usd_auction", "1.60", false, "250")),
                        List.of("BD"), 1_500_000,
                        List.of("shopping", "ecommerce", "retail", "deals", "fashion", "fintech", "payments", "buyers"),
                        List.of("shop_sidebar"))
        );
        this.products = seed.stream().collect(Collectors.toMap(Product::productId, Function.identity()));
    }

    private static PricingOption cpm(String id, String rate, boolean fixed, String minSpend) {
        return new PricingOption(id, "cpm", new BigDecimal(rate), "USD", fixed, new BigDecimal(minSpend));
    }

    /**
     * Naive keyword scoring of the buyer's brief against product keywords.
     * Empty/unmatched brief returns the full catalog so the buyer always sees something.
     * This is the obvious place to plug in embeddings or an LLM ranker later.
     */
    public List<Product> search(String brief) {
        if (brief == null || brief.isBlank()) {
            return List.copyOf(products.values());
        }
        Set<String> terms = Arrays.stream(brief.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> t.length() > 1 && !STOP_WORDS.contains(t))
                .collect(Collectors.toSet());

        List<Product> ranked = products.values().stream()
                .map(p -> Map.entry(p, score(p, terms)))
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<Product, Integer>comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .toList();
        return ranked.isEmpty() ? List.copyOf(products.values()) : ranked;
    }

    private int score(Product p, Set<String> terms) {
        String haystack = (p.name() + " " + p.description()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String t : terms) {
            if (p.keywords().contains(t)) score += 3;
            else if (haystack.contains(t)) score += 1;
        }
        return score;
    }

    public Optional<Product> find(String productId) {
        return Optional.ofNullable(products.get(productId));
    }

    public Optional<CreativeFormat> format(String formatId) {
        return formats.stream().filter(f -> f.formatId().equals(formatId)).findFirst();
    }

    public List<CreativeFormat> formats() {
        return formats;
    }
}
