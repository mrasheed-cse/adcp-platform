package com.adcpplatform.salesagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @param specVersion AdCP spec version advertised in get_adcp_capabilities
 * @param accounts    buyer accounts and their bearer tokens; empty (or all tokens blank) = auth disabled
 */
@ConfigurationProperties(prefix = "adcp")
public record AdcpProperties(String specVersion, List<Account> accounts) {

    public record Account(String accountId, String token) {}

    public AdcpProperties {
        accounts = accounts == null ? List.of()
                : accounts.stream().filter(a -> a.token() != null && !a.token().isBlank()).toList();
    }

    public boolean authEnabled() {
        return !accounts.isEmpty();
    }

    public Optional<String> accountForToken(String token) {
        return Optional.ofNullable(tokenIndex().get(token));
    }

    private Map<String, String> tokenIndex() {
        return accounts.stream().collect(Collectors.toMap(Account::token, Account::accountId, (a, b) -> a));
    }
}
