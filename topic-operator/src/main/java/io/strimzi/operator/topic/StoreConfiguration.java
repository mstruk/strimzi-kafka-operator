/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.apicurio.registry.streams.diservice.AsyncBiFunctionService;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

/**
 * SPI for the store configuration.
 * * in-memory / local
 * * distributed
 */
interface StoreConfiguration {
    void configure(
            Config config,
            Properties kafkaProperties,
            KafkaStreams streams,
            AsyncBiFunctionService.WithSerdes<String, String, Integer> lookupService,
            List<AutoCloseable> closeables
    );
    ReadOnlyKeyValueStore<String, Topic> getStore();
    BiFunction<String, String, CompletionStage<Integer>> getLookupService();
}
