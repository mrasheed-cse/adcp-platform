package com.adcpplatform.trafficking.domain;

import java.security.SecureRandom;
import java.util.HexFormat;

/** Prefixed random ids, e.g. li_3f9a1c0b2d4e. Readable in logs and URLs, unguessable. */
public final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static String next(String prefix) {
        byte[] b = new byte[6];
        RANDOM.nextBytes(b);
        return prefix + "_" + HexFormat.of().formatHex(b);
    }
}
