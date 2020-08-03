/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.strimzi.operator.topic.zk.Zk;
import io.vertx.core.Future;

import java.util.Properties;

/**
 * Migration tool to move ZkTopicStore to KafkaStreamsTopicStore.
 */
public class Zk2KafkaStreams {

    public static void main(String[] args) {
        // TODO
    }

    public static void move(Zk zk, Config config, Properties kafkaProperties) {
        String topicsPath = config.get(Config.TOPICS_PATH);
        TopicStore zkTopicStore = new ZkTopicStore(zk, topicsPath);

        KafkaStreamsConfiguration configuration = new KafkaStreamsConfiguration();
        try {
            configuration.start(config, kafkaProperties);
            TopicStore ksTopicStore = configuration.getTopicStore();

            zk.children(topicsPath, result -> result.map(list -> {
                list.forEach(topicName -> {
                    Future<Topic> ft = zkTopicStore.read(new TopicName(topicName));
                    ft.onSuccess(ksTopicStore::create);
                });
                return null;
            }));
        } finally {
            configuration.stop();
        }
    }
}
