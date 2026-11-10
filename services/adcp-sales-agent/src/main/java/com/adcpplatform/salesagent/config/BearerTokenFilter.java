package com.adcpplatform.salesagent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Resolves the caller's bearer token to a buyer account and stores it on the request.
 * Unknown or missing tokens get 401 before reaching the MCP endpoint.
 * Replace with Spring Security + OAuth2 resource server for production.
 */
@Component
public class BearerTokenFilter extends OncePerRequestFilter {

    public static final String ACCOUNT_ATTRIBUTE = "adcp.accountId";
    private static final String PREFIX = "Bearer ";

    private final AdcpProperties props;

    public BearerTokenFilter(AdcpProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.authEnabled() || !request.getRequestURI().startsWith("/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        Optional<String> account = (header != null && header.startsWith(PREFIX))
                ? props.accountForToken(header.substring(PREFIX.length()).trim())
                : Optional.empty();

        if (account.isEmpty()) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setHeader("WWW-Authenticate", "Bearer");
            return;
        }
        req.setAttribute(ACCOUNT_ATTRIBUTE, account.get());
        chain.doFilter(req, res);
    }
}
