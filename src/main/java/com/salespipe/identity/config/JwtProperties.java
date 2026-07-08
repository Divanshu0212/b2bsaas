package com.salespipe.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {
    private String secret;
    private long accessTtlSeconds;
    private long refreshTtlSeconds;
    public String getSecret() { return secret; }
    public void setSecret(String s) { this.secret = s; }
    public long getAccessTtlSeconds() { return accessTtlSeconds; }
    public void setAccessTtlSeconds(long v) { this.accessTtlSeconds = v; }
    public long getRefreshTtlSeconds() { return refreshTtlSeconds; }
    public void setRefreshTtlSeconds(long v) { this.refreshTtlSeconds = v; }
}
