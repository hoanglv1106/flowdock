package io.github.hoanglv1106.flowdock.core;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in real infrastructure checks. No mocks or application payment processing. */
class InfrastructureIT {
    private static String env(String key, String fallback) {
        return System.getenv().getOrDefault(key, fallback);
    }

    @Test
    void migrationWorksOnANewDedicatedDatabaseAndIsRepeatable() throws Exception {
        String baseUrl = env("FLOWDOCK_DB_URL", "jdbc:postgresql://127.0.0.1:15432/flowdock");
        String user = env("FLOWDOCK_DB_USER", "flowdock_lab");
        String password = env("FLOWDOCK_DB_PASSWORD", "flowdock_lab_only");
        String database = "flowdock_verify_" + UUID.randomUUID().toString().replace("-", "");
        // Identifiers are generated locally from hex; never take SQL identifiers from input.
        try (var connection = DriverManager.getConnection(baseUrl, user, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String freshUrl = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1) + database;
        try {
            var flyway = Flyway.configure().dataSource(freshUrl, user, password).cleanDisabled(true).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(freshUrl, user, password);
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT obj_description(oid) FROM pg_namespace WHERE nspname = 'flowdock'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("FlowDock lab application namespace");
            }
            System.out.printf("POSTGRES_EVIDENCE database=%s migration=1 secondMigrationCount=0%n", database);
        } finally {
            // Only this test-created UUID database is removed; never clean the application DB.
            try (var connection = DriverManager.getConnection(baseUrl, user, password);
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }

    @Test
    void hostCanPublishAndConsumeAnActualRecordAtItsAcknowledgedCoordinate() throws Exception {
        String bootstrap = env("FLOWDOCK_KAFKA_BOOTSTRAP", "127.0.0.1:19092");
        String topic = "flowdock.payment.events";
        try (var admin = Admin.create(Map.of("bootstrap.servers", bootstrap,
                "default.api.timeout.ms", "20000", "request.timeout.ms", "10000"))) {
            var topics = admin.describeTopics(List.of(topic, "flowdock.payment.dlq")).allTopicNames().get(25, TimeUnit.SECONDS);
            topics.values().forEach(description -> {
                assertThat(description.partitions()).hasSize(3);
                description.partitions().forEach(partition -> assertThat(partition.replicas()).hasSize(1));
            });
        }
        String key = "foundation-" + UUID.randomUUID();
        String payload = "{\"kind\":\"foundation-connectivity\",\"fixtureId\":\"" + key + "\"}";
        try (var producer = new KafkaProducer<String, String>(Map.of(
                "bootstrap.servers", bootstrap, "key.serializer", StringSerializer.class,
                "value.serializer", StringSerializer.class, "acks", "all",
                "max.block.ms", "20000", "delivery.timeout.ms", "30000", "request.timeout.ms", "10000"))) {
            var sent = producer.send(new ProducerRecord<>(topic, 0, key, payload)).get(35, TimeUnit.SECONDS);
            var partition = new TopicPartition(sent.topic(), sent.partition());
            try (var consumer = new KafkaConsumer<String, String>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                    ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 20000))) {
                // Direct assignment + seek reads only this fixture without touching worker offsets.
                consumer.assign(List.of(partition));
                consumer.seek(partition, sent.offset());
                long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
                boolean matched = false;
                while (!matched && System.nanoTime() < deadline) {
                    for (var record : consumer.poll(Duration.ofSeconds(1))) {
                        if (record.offset() == sent.offset()) {
                            assertThat(record.key()).isEqualTo(key);
                            assertThat(record.value()).isEqualTo(payload);
                            matched = true;
                        }
                    }
                }
                assertThat(matched).as("acknowledged fixture must be consumed before deadline").isTrue();
            }
            System.out.printf("KAFKA_EVIDENCE bootstrap=%s topic=%s partition=%d offset=%d fixtureId=%s%n",
                    bootstrap, sent.topic(), sent.partition(), sent.offset(), key);
        }
    }
}
