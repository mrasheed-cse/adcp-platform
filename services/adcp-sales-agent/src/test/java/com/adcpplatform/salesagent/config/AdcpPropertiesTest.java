package com.adcpplatform.salesagent.config;

import com.adcpplatform.salesagent.config.AdcpProperties.Account;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdcpPropertiesTest {

    @Test
    void blankTokensDisableAuth() {
        var props = new AdcpProperties("3.1", List.of(new Account("demo-buyer", ""), new Account("other", null)));
        assertFalse(props.authEnabled());
    }

    @Test
    void resolvesAccountByToken() {
        var props = new AdcpProperties("3.1", List.of(
                new Account("demo-buyer", "token-aaaaaaaaaaaaaaaa"),
                new Account("second-buyer", "token-bbbbbbbbbbbbbbbb")));
        assertTrue(props.authEnabled());
        assertEquals(Optional.of("second-buyer"), props.accountForToken("token-bbbbbbbbbbbbbbbb"));
        assertEquals(Optional.empty(), props.accountForToken("wrong"));
    }
}
