package com.salespipe.eventing.schema;

import com.salespipe.eventing.EventingProperties;
import com.salespipe.eventing.Topics;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * Registers one JSON Schema per topic (see {@code eventing/schema/*.json} under
 * {@code src/main/resources/eventing/schema}) with Schema Registry at startup.
 * Subject naming follows the standard TopicNameStrategy: {@code <topic>-value}.
 *
 * <p>Registration is best-effort and non-fatal: if Schema Registry is briefly
 * unavailable at boot, the app still starts (producers/consumers will surface
 * a clear error on first use instead). This keeps local `bootRun` usable when
 * only Postgres/Redis are up.
 */
@Configuration
@EnableConfigurationProperties(EventingProperties.class)
public class SchemaRegistrationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistrationRunner.class);

    @Bean
    public ApplicationRunner registerEventSchemas(EventingProperties props) {
        return (ApplicationArguments args) -> {
            SchemaRegistryClient client = new CachedSchemaRegistryClient(props.getSchemaRegistryUrl(), 100);
            for (String topic : Topics.ALL) {
                registerOne(client, topic);
            }
        };
    }

    private void registerOne(SchemaRegistryClient client, String topic) {
        String subject = topic + "-value";
        try {
            String schemaJson = readSchemaFile(topic);
            client.register(subject, new JsonSchema(schemaJson));
            log.info("Registered schema for subject {}", subject);
        } catch (Exception e) {
            log.warn("Could not register schema for subject {} (schema registry may be unavailable): {}", subject, e.getMessage());
        }
    }

    private String readSchemaFile(String topic) throws IOException {
        String resourcePath = "eventing/schema/" + topic + ".json";
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
