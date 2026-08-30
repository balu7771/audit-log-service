package com.persistent.auditlog.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Per-caller RBAC gate on the /audit/** API surface: the {@code X-API-Key}
 * header is matched against the configured {@code audit.security.api-keys}
 * list, and the matching entry's roles are loaded into the security context
 * as granted authorities. Role-to-endpoint enforcement itself lives in
 * {@code @PreAuthorize} annotations on controller methods (see
 * {@link SecurityConfig}) — this filter is only responsible for
 * authentication (who is this caller), not authorization (what can they do).
 */
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final AuditApiKeyProperties apiKeyProperties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/audit");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String providedKey = request.getHeader(API_KEY_HEADER);
        Optional<AuditApiKeyProperties.ApiKeyEntry> matched = findByKey(providedKey);

        if (matched.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("text/plain");
            response.getWriter().write("Missing or invalid " + API_KEY_HEADER + " header");
            return;
        }

        AuditApiKeyProperties.ApiKeyEntry entry = matched.get();
        SecurityContextHolder.getContext().setAuthentication(
            new ApiKeyAuthenticationToken(entry.getName(), entry.getRoles()));
        filterChain.doFilter(request, response);
    }

    private Optional<AuditApiKeyProperties.ApiKeyEntry> findByKey(String providedKey) {
        if (providedKey == null || providedKey.isEmpty()) {
            return Optional.empty();
        }
        return apiKeyProperties.getApiKeys().stream()
            .filter(entry -> entry.getKey() != null && entry.getKey().equals(providedKey))
            .findFirst();
    }
}
