package com.salespipe.eventing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.eventing")
public class EventingProperties {
    private String bootstrapServers;
    private String schemaRegistryUrl;

    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String v) { this.bootstrapServers = v; }
    public String getSchemaRegistryUrl() { return schemaRegistryUrl; }
    public void setSchemaRegistryUrl(String v) { this.schemaRegistryUrl = v; }
}
