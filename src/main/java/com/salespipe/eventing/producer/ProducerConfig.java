package com.salespipe.eventing.producer;

import com.salespipe.eventing.EventEnvelope;
import com.salespipe.eventing.EventingProperties;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.json.KafkaJsonSchemaSerializer;
import io.confluent.kafka.serializers.json.KafkaJsonSchemaSerializerConfig;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Shared Kafka producer wiring for the eventing module. Key = String (aggregate_id,
 * for per-aggregate ordering per overview §5); value = {@link EventEnvelope} serialized
 * with the Confluent JSON Schema serializer, validated against the schema registered
 * for {@code <topic>-value} (see {@link SchemaRegistrationRunner}).
 */
@Configuration
@EnableConfigurationProperties(EventingProperties.class)
public class ProducerConfig {

    @Bean
    public ProducerFactory<String, EventEnvelope> eventProducerFactory(EventingProperties props) {
        Map<String, Object> config = new HashMap<>();
        config.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getBootstrapServers());
        config.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaJsonSchemaSerializer.class);
        config.put(org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG, "all");
        config.put(org.apache.kafka.clients.producer.ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(KafkaJsonSchemaSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, props.getSchemaRegistryUrl());
        config.put(KafkaJsonSchemaSerializerConfig.AUTO_REGISTER_SCHEMAS, false);
        // Use whatever's already registered for the subject (SchemaRegistrationRunner owns
        // registration at startup from eventing/schema/*.json) instead of trying to
        // register/match a reflectively-derived schema for EventEnvelope.
        config.put(KafkaJsonSchemaSerializerConfig.USE_LATEST_VERSION, true);
        // The serializer would otherwise reject sends because the reflection-derived
        // schema for EventEnvelope.class doesn't structurally equal our hand-written
        // schema (extra strictness, e.g. additionalProperties/oneOf-null shape) — we
        // intentionally hand-author schemas per topic, so skip that client-side check.
        config.put(KafkaJsonSchemaSerializerConfig.LATEST_COMPATIBILITY_STRICT, false);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, EventEnvelope> eventKafkaTemplate(ProducerFactory<String, EventEnvelope> eventProducerFactory) {
        return new KafkaTemplate<>(eventProducerFactory);
    }
}
