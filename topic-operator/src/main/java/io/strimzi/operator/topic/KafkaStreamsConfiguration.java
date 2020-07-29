/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.apicurio.registry.streams.diservice.AsyncBiFunctionService;
import io.apicurio.registry.streams.diservice.AsyncBiFunctionServiceGrpcLocalDispatcher;
import io.apicurio.registry.streams.diservice.DefaultGrpcChannelProvider;
import io.apicurio.registry.streams.diservice.DistributedAsyncBiFunctionService;
import io.apicurio.registry.streams.diservice.LocalService;
import io.apicurio.registry.streams.diservice.proto.AsyncBiFunctionServiceGrpc;
import io.apicurio.registry.streams.distore.DistributedReadOnlyKeyValueStore;
import io.apicurio.registry.streams.distore.FilterPredicate;
import io.apicurio.registry.streams.distore.KeyValueSerde;
import io.apicurio.registry.streams.distore.KeyValueStoreGrpcImplLocalDispatcher;
import io.apicurio.registry.streams.distore.UnknownStatusDescriptionInterceptor;
import io.apicurio.registry.streams.distore.proto.KeyValueStoreGrpc;
import io.apicurio.registry.streams.utils.ForeachActionDispatcher;
import io.apicurio.registry.streams.utils.Lifecycle;
import io.apicurio.registry.streams.utils.LoggingStateRestoreListener;
import io.apicurio.registry.utils.ConcurrentUtil;
import io.apicurio.registry.utils.kafka.AsyncProducer;
import io.apicurio.registry.utils.kafka.ProducerActions;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.HostInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Configuration required for KafkaStreamsTopicStore
 */
public class KafkaStreamsConfiguration {
    private static final Logger log = LoggerFactory.getLogger(KafkaStreamsConfiguration.class);

    private final List<AutoCloseable> closeables = new ArrayList<>();

    /* test */ KafkaStreams streams;
    private TopicStore topicStore;

    public void start(Config config, Properties kafkaProperties) {
        try {
            String storeTopic = config.get(Config.STORE_TOPIC);
            String storeName = config.get(Config.STORE_NAME);

            long timeoutMillis = config.get(Config.STALE_RESULT_TIMEOUT);
            ForeachActionDispatcher<String, Integer> dispatcher = new ForeachActionDispatcher<>();
            WaitForResultService serviceImpl = new WaitForResultService(timeoutMillis, dispatcher);
            closeables.add(serviceImpl);

            Topology topology = new TopicStoreTopologyProvider(storeTopic, storeName, kafkaProperties, dispatcher).get();
            streams = new KafkaStreams(topology, kafkaProperties);
            streams.setGlobalStateRestoreListener(new LoggingStateRestoreListener());
            closeables.add(streams);
            streams.start();

            String appServer = config.get(Config.APPLICATION_SERVER);
            String[] hostPort = appServer.split(":");
            log.info("Application server gRPC: '{}'", appServer);
            HostInfo hostInfo = new HostInfo(hostPort[0], Integer.parseInt(hostPort[1]));

            FilterPredicate<String, Topic> filter = (s, s1, s2, topic) -> true;

            DistributedReadOnlyKeyValueStore<String, Topic> store = new DistributedReadOnlyKeyValueStore<>(
                    streams,
                    hostInfo,
                    storeName,
                    Serdes.String(),
                    new TopicSerde(),
                    new DefaultGrpcChannelProvider(),
                    true,
                    filter
            );
            closeables.add(store);

            ProducerActions<String, TopicCommand> producer = new AsyncProducer<>(
                    kafkaProperties,
                    Serdes.String().serializer(),
                    new TopicCommandSerde()
            );
            closeables.add(producer);

            LocalService<AsyncBiFunctionService.WithSerdes<String, String, Integer>> localService =
                    new LocalService<>(WaitForResultService.NAME, serviceImpl);
            AsyncBiFunctionService<String, String, Integer> service = new DistributedAsyncBiFunctionService<>(
                    streams, hostInfo, storeName, localService, new DefaultGrpcChannelProvider()
            );
            closeables.add(service);

            // gRPC

            KeyValueStoreGrpc.KeyValueStoreImplBase kvGrpc = streamsKeyValueStoreGrpcImpl(streams, storeName, filter);
            AsyncBiFunctionServiceGrpc.AsyncBiFunctionServiceImplBase fnGrpc = streamsAsyncBiFunctionServiceGrpcImpl(localService);
            Lifecycle server = streamsGrpcServer(hostInfo, kvGrpc, fnGrpc);
            server.start();
            AutoCloseable serverCloseable = server::stop;
            closeables.add(serverCloseable);

            topicStore = new KafkaStreamsTopicStore(store, storeTopic, producer, service);
        } catch (Exception e) {
            stop(); // stop what we already started for any exception
            throw e;
        }
    }

    private KeyValueStoreGrpc.KeyValueStoreImplBase streamsKeyValueStoreGrpcImpl(
            KafkaStreams streams,
            String storeName,
            FilterPredicate<String, Topic> filterPredicate
    ) {
        return new KeyValueStoreGrpcImplLocalDispatcher(
                streams,
                KeyValueSerde
                        .newRegistry()
                        .register(
                                storeName,
                                Serdes.String(), new TopicSerde()
                        ),
                filterPredicate
        );
    }

    private AsyncBiFunctionServiceGrpc.AsyncBiFunctionServiceImplBase streamsAsyncBiFunctionServiceGrpcImpl(
            LocalService<AsyncBiFunctionService.WithSerdes<String, String, Integer>> localWaitForResultService
    ) {
        return new AsyncBiFunctionServiceGrpcLocalDispatcher(Collections.singletonList(localWaitForResultService));
    }

    private Lifecycle streamsGrpcServer(
            HostInfo localHost,
            KeyValueStoreGrpc.KeyValueStoreImplBase streamsStoreGrpcImpl,
            AsyncBiFunctionServiceGrpc.AsyncBiFunctionServiceImplBase streamsAsyncBiFunctionServiceGrpcImpl
    ) {
        UnknownStatusDescriptionInterceptor unknownStatusDescriptionInterceptor =
                new UnknownStatusDescriptionInterceptor(
                        Map.of(
                                IllegalArgumentException.class, Status.INVALID_ARGUMENT,
                                IllegalStateException.class, Status.FAILED_PRECONDITION,
                                InvalidStateStoreException.class, Status.FAILED_PRECONDITION,
                                Throwable.class, Status.INTERNAL
                        )
                );

        Server server = ServerBuilder
                .forPort(localHost.port())
                .addService(
                        ServerInterceptors.intercept(
                                streamsStoreGrpcImpl,
                                unknownStatusDescriptionInterceptor
                        )
                )
                .addService(
                        ServerInterceptors.intercept(
                                streamsAsyncBiFunctionServiceGrpcImpl,
                                unknownStatusDescriptionInterceptor
                        )
                )
                .build();

        return new Lifecycle() {
            @Override
            public void start() {
                try {
                    server.start();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public void stop() {
                ConcurrentUtil
                        .<Server>consumer(Server::awaitTermination)
                        .accept(server.shutdown());
            }

            @Override
            public boolean isRunning() {
                return !(server.isShutdown() || server.isTerminated());
            }
        };
    }

    public void stop() {
        Collections.reverse(closeables);
        closeables.forEach(KafkaStreamsConfiguration::close);
    }

    public TopicStore getTopicStore() {
        return topicStore;
    }

    private static void close(AutoCloseable service) {
        try {
            service.close();
        } catch (Exception e) {
            log.warn("Exception while closing service: {}", service, e);
        }
    }
}
