package com.salespipe.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * T6.3: allowed browser origins for the Next.js frontend. Explicit list (not a wildcard)
 * because CORS credentials are enabled for the httpOnly refresh cookie. Configured via
 * {@code app.cors.allowed-origins} (comma-separated), defaulted to localhost dev origins.
 */
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {
    private List<String> allowedOrigins = List.of("http://localhost:3000");

    public List<String> allowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> v) { this.allowedOrigins = v; }
}
