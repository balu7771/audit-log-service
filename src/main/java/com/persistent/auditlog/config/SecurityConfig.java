package com.persistent.auditlog.config;

import org.springframework.boot.autoconfigure.security.servlet.PathRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpStatus;

/**
 * Wires {@link ApiKeyAuthFilter} into Spring Security's filter chain and
 * declares the RBAC role hierarchy: ADMIN is a superset of both AUDITOR and
 * WRITER (an operator who can archive/redact records can also read and write
 * them), but AUDITOR and WRITER are otherwise disjoint — a read-only
 * compliance caller cannot write events, and an event-producing caller
 * cannot read/export the log. Per-endpoint role requirements live as
 * {@code @PreAuthorize} annotations on the controller methods themselves so
 * the required role is visible right next to the operation it guards.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AuditApiKeyProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyAuthFilter apiKeyAuthFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
                .requestMatchers("/actuator/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(handling -> handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(apiKeyAuthFilter, org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class);
        return http.build();
    }

    /**
     * {@link ApiKeyAuthFilter} is a {@code @Component} so it can receive
     * {@link AuditApiKeyProperties} via constructor injection, but it must only run
     * once, as part of the chain wired above — without this, Spring Boot would also
     * auto-register it as a plain servlet filter applied to every request a second time.
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> disableAutomaticFilterRegistration(ApiKeyAuthFilter filter) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
            .role("ADMIN").implies("AUDITOR")
            .role("ADMIN").implies("WRITER")
            .build();
    }
}
