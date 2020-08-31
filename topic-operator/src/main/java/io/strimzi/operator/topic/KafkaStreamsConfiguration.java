/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.apicurio.registry.utils.kafka.AsyncProducer;
import io.apicurio.registry.utils.kafka.ProducerActions;
import io.apicurio.registry.utils.streams.ext.ForeachActionDispatcher;
import io.apicurio.registry.utils.streams.ext.LoggingStateRestoreListener;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

import static java.lang.Integer.parseInt;

/**
 * Configuration required for KafkaStreamsTopicStore
 */
public class KafkaStreamsConfiguration {
    private static final Logger log = LoggerFactory.getLogger(KafkaStreamsConfiguration.class);

    private final List<AutoCloseable> closeables = new ArrayList<>();

    /* test */ KafkaStreams streams;
    /* test */ TopicStore store;

    public CompletionStage<TopicStore> start(Config config, Properties kafkaProperties) {
        String storeTopic = config.get(Config.STORE_TOPIC);
        String storeName = config.get(Config.STORE_NAME);

        // check if entry topic has the right configuration
        Admin admin = Admin.create(kafkaProperties);
        return toCS(admin.describeCluster().nodes())
                .thenApply(nodes -> new Context(nodes.size()))
                .thenCompose(c -> toCS(admin.listTopics().names()).thenApply(c::setTopics))
                .thenCompose(c -> {
                    if (c.topics.contains(storeTopic)) {
                        ConfigResource storeTopicConfigResource = new ConfigResource(ConfigResource.Type.TOPIC, storeTopic);
                        return toCS(admin.describeTopics(Collections.singleton(storeTopic)).values().get(storeTopic))
                            .thenApply(td -> c.setRf(td.partitions().stream().map(tp -> tp.replicas().size()).min(Integer::compare).orElseThrow()))
                            .thenCompose(c2 -> toCS(admin.describeConfigs(Collections.singleton(storeTopicConfigResource)).values().get(storeTopicConfigResource))
                                    .thenApply(cr -> c2.setMinISR(parseInt(cr.get(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG).value()))))
                            .thenApply(c3 -> {
                                if (c3.rf != Math.min(3, c3.clusterSize) || c3.minISR != c3.rf - 1) {
                                    log.warn("Durability of the topic [{}] is not sufficient for production use - replicationFactor: {}, {}: {}. " +
                                            "Increase the replication factor to at least 3 and configure the {} to {}.",
                                            storeTopic, c3.rf, TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, c3.minISR, TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, c3.minISR);
                                }
                                return null;
                            });
                    } else {
                        int rf = Math.min(3, c.clusterSize);
                        int minISR = rf - 1;
                        NewTopic newTopic = new NewTopic(storeTopic, 1, (short) rf)
                            .configs(Collections.singletonMap(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(minISR)));
                        return toCS(admin.createTopics(Collections.singleton(newTopic)).all());
                    }
                })
                .thenApply(v -> {
                    long timeoutMillis = config.get(Config.STALE_RESULT_TIMEOUT_MS);
                    ForeachActionDispatcher<String, Integer> dispatcher = new ForeachActionDispatcher<>();
                    WaitForResultService serviceImpl = new WaitForResultService(timeoutMillis, dispatcher);
                    closeables.add(serviceImpl);

                    Topology topology = new TopicStoreTopologyProvider(storeTopic, storeName, kafkaProperties, dispatcher).get();
                    streams = new KafkaStreams(topology, kafkaProperties);
                    streams.setGlobalStateRestoreListener(new LoggingStateRestoreListener());
                    closeables.add(streams);
                    streams.start();

                    ProducerActions<String, TopicCommand> producer = new AsyncProducer<>(
                        kafkaProperties,
                        Serdes.String().serializer(),
                        new TopicCommandSerde()
                    );
                    closeables.add(producer);

                    StoreConfiguration storeConfiguration;
                    if (config.get(Config.DISTRIBUTED_STORE)) {
                        storeConfiguration = new DistributedStoreConfiguration();
                    } else {
                        storeConfiguration = new LocalStoreConfiguration();
                    }
                    storeConfiguration.configure(config, kafkaProperties, streams, serviceImpl, closeables);
                    ReadOnlyKeyValueStore<String, Topic> store = storeConfiguration.getStore();
                    BiFunction<String, String, CompletionStage<Integer>> service = storeConfiguration.getLookupService();

                    this.store = new KafkaStreamsTopicStore(store, storeTopic, producer, service);
                    return this.store;
                }
                ).whenComplete((v, t) -> {
                    if (t != null) {
                        stop();
                    }
                });
    }

    public void stop() {
        Collections.reverse(closeables);
        closeables.forEach(KafkaStreamsConfiguration::close);
    }

    private static void close(AutoCloseable service) {
        try {
            service.close();
        } catch (Exception e) {
            log.warn("Exception while closing service: {}", service, e);
        }
    }

    private static <T> CompletionStage<T> toCS(KafkaFuture<T> kf) {
        CompletableFuture<T> cf = new CompletableFuture<>();
        kf.whenComplete((v, t) -> {
            if (t != null) {
                cf.completeExceptionally(t);
            } else {
                cf.complete(v);
            }
        });
        return cf;
    }

    static class Context {
        int clusterSize;
        Set<String> topics;
        int rf;
        int minISR;

        public Context(int clusterSize) {
            this.clusterSize = clusterSize;
        }

        public Context setTopics(Set<String> topics) {
            this.topics = topics;
            return this;
        }

        public Context setRf(int rf) {
            this.rf = rf;
            return this;
        }

        public Context setMinISR(int minISR) {
            this.minISR = minISR;
            return this;
        }
    }

}
