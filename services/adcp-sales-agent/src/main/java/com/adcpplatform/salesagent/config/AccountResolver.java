package com.adcpplatform.salesagent.config;

import com.adcpplatform.salesagent.mediabuy.AdcpException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Finds the buyer account for the current tool call.
 *
 * Fails closed: if auth is on but no account is visible on this thread, the call is rejected
 * rather than falling back to a shared account. If you see UNAUTHENTICATED on every call with a
 * valid token, the MCP transport is running tools off the servlet thread; switch to reading the
 * account from the MCP exchange / transport context instead (see README).
 */
@Component
public class AccountResolver {

    public static final String LOCAL_DEV_ACCOUNT = "local-dev";

    private final AdcpProperties props;

    public AccountResolver(AdcpProperties props) {
        this.props = props;
    }

    public String currentAccount() {
        if (!props.authEnabled()) {
            return LOCAL_DEV_ACCOUNT;
        }
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        Object account = attrs == null ? null
                : attrs.getAttribute(BearerTokenFilter.ACCOUNT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (account instanceof String id && !id.isBlank()) {
            return id;
        }
        throw new AdcpException("UNAUTHENTICATED", "no authenticated buyer account for this call", null);
    }
}
