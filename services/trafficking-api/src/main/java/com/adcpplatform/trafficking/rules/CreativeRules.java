package com.adcpplatform.trafficking.rules;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import com.adcpplatform.trafficking.domain.CreativeType;

public final class CreativeRules {

    private CreativeRules() {}

    public static List<FieldError> validate(String name, CreativeType type, int width, int height,
                                            String imageUrl, String html, String landingUrl) {
        List<FieldError> errors = new ArrayList<>();
        if (name == null || name.isBlank()) {
            errors.add(new FieldError("name", "is required"));
        }
        if (type == null) {
            errors.add(new FieldError("type", "must be image or html"));
        }
        if (width < 1 || width > 4000 || height < 1 || height > 4000) {
            errors.add(new FieldError("width", "width and height must be between 1 and 4000"));
        }
        if (type == CreativeType.IMAGE && !isHttpUrl(imageUrl)) {
            errors.add(new FieldError("image_url", "an image creative needs an http(s) image_url"));
        }
        if (type == CreativeType.HTML && (html == null || html.isBlank())) {
            errors.add(new FieldError("html", "an html creative needs html"));
        }
        if (!isHttpUrl(landingUrl)) {
            errors.add(new FieldError("landing_url", "must be an http(s) URL"));
        }
        return errors;
    }

    static boolean isHttpUrl(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        try {
            URI u = new URI(s);
            return ("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme()))
                    && u.getHost() != null && !u.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }
}
