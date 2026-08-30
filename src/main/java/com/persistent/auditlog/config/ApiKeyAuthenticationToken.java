package com.persistent.auditlog.config;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authentication populated by {@link ApiKeyAuthFilter} once a request's
 * X-API-Key header has been matched to a configured caller. The principal is
 * the caller's configured {@code name} (for log/audit correlation), never the
 * key itself.
 */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final String callerName;

    public ApiKeyAuthenticationToken(String callerName, Set<ApiRole> roles) {
        super(toAuthorities(roles));
        this.callerName = callerName;
        setAuthenticated(true);
    }

    private static Collection<GrantedAuthority> toAuthorities(Set<ApiRole> roles) {
        return roles.stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
            .collect(Collectors.toSet());
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return callerName;
    }
}
