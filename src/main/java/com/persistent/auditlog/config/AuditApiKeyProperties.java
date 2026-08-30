package com.persistent.auditlog.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * Binds the {@code audit.security.api-keys} list: each entry is one caller's
 * shared secret plus the roles it is trusted for. Replaces the single
 * shared-secret-for-everyone model (one key, no roles) with per-caller
 * least-privilege credentials.
 */
@Data
@ConfigurationProperties(prefix = "audit.security")
public class AuditApiKeyProperties {

    private List<ApiKeyEntry> apiKeys = List.of();

    @Data
    public static class ApiKeyEntry {
        /** The shared secret presented via the X-API-Key header. */
        private String key;
        /** Human-readable caller identity, used for audit/log correlation only. */
        private String name;
        /** Roles granted to this key. */
        private Set<ApiRole> roles = Set.of();
    }
}
