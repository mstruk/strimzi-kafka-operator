/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.apicurio.registry.utils.streams.diservice.AsyncBiFunctionService;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

/**
 * Simple local / in-memory store configuration which is sufficient when the TO runs in a single pod
 */
class LocalStoreConfiguration implements StoreConfiguration {
    private ReadOnlyKeyValueStore<String, Topic> store;
    private BiFunction<String, String, CompletionStage<Integer>> lookupService;

    @Override
    public ReadOnlyKeyValueStore<String, Topic> getStore() {
        return store;
    }

    @Override
    public BiFunction<String, String, CompletionStage<Integer>> getLookupService() {
        return lookupService;
    }

    @SuppressWarnings("unchecked")
    public void configure(
            Config config,
            Properties kafkaProperties,
            KafkaStreams streams,
            AsyncBiFunctionService.WithSerdes<String, String, Integer> serviceImpl,
            List<AutoCloseable> closeables
    ) {
        String storeName = config.get(Config.STORE_NAME);
        // we need to lazily create the store as streams might not be ready yet
        store = (ReadOnlyKeyValueStore<String, Topic>) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{ReadOnlyKeyValueStore.class},
                new InvocationHandler() {
                    private ReadOnlyKeyValueStore<String, Topic> store;

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (store == null) {
                            synchronized (this) {
                                if (store == null) {
                                    store = streams.store(StoreQueryParameters.fromNameAndType(storeName, QueryableStoreTypes.keyValueStore()));
                                }
                            }
                        }
                        try {
                            return method.invoke(store, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                }
        );
        lookupService = serviceImpl;
    }
}
