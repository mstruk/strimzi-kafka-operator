/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxTestContext;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class KafkaStreamsTopicStoreTest extends TopicStoreTestBase {

    private static final Map<String, String> MANDATORY_CONFIG;

    static {
        MANDATORY_CONFIG = new HashMap<>();
        MANDATORY_CONFIG.put(Config.ZOOKEEPER_CONNECT.key, "localhost:2181");
        MANDATORY_CONFIG.put(Config.KAFKA_BOOTSTRAP_SERVERS.key, "localhost:9092");
        MANDATORY_CONFIG.put(Config.NAMESPACE.key, "default");
    }

    private static KafkaStreamsConfiguration configuration;

    @Override
    protected boolean canRunTest() {
        return isKafkaAvailable();
    }

    static boolean isKafkaAvailable() {
        try {
            Properties kafkaProperties = new Properties();
            kafkaProperties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
            try (AdminClient adminClient = AdminClient.create(kafkaProperties)) {
                adminClient.listTopics().names().get();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static KafkaStreamsConfiguration configuration(Map<String, String> configMap) throws Exception {
        if (!isKafkaAvailable()) {
            return null;
        }

        Map<String, String> mergedMap = new HashMap<>(MANDATORY_CONFIG);
        mergedMap.putAll(configMap);
        Config config = new Config(mergedMap);

        Properties kafkaProperties = new Properties();
        kafkaProperties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, config.get(Config.KAFKA_BOOTSTRAP_SERVERS));
        kafkaProperties.put(StreamsConfig.APPLICATION_ID_CONFIG, config.get(Config.APPLICATION_ID));
        kafkaProperties.put(StreamsConfig.APPLICATION_SERVER_CONFIG, config.get(Config.APPLICATION_SERVER));

        String storeTopic = config.get(Config.STORE_TOPIC);

        try (AdminClient adminClient = AdminClient.create(kafkaProperties)) {
            Set<String> topics = adminClient.listTopics().names().get();
            if (!topics.contains(storeTopic)) {
                adminClient.createTopics(Collections.singleton(new NewTopic(storeTopic, 3, (short) 1))).all().get();
            }
        }

        KafkaStreamsConfiguration configuration = new KafkaStreamsConfiguration();
        configuration.start(config, kafkaProperties);

        KafkaStreams ks = configuration.streams;
        int attempts = 50; // 5sec
        while (ks.state() != KafkaStreams.State.RUNNING) {
            //noinspection BusyWait
            Thread.sleep(100);
            attempts--;
            if (attempts == 0) {
                throw new IllegalStateException("Cannot start Kafka Streams!");
            }
        }

        return configuration;
    }

    @BeforeAll
    public static void before() throws Exception {
        configuration = configuration(Collections.emptyMap());
    }

    @AfterAll
    public static void after() {
        if (configuration != null) {
            configuration.stop();
        }
    }

    @BeforeEach
    public void setup() {
        if (configuration != null) {
            this.store = configuration.getTopicStore();
        }
    }

    @AfterEach
    public void teardown(VertxTestContext context) {
        Checkpoint async = context.checkpoint();
        async.flag();
    }

}
