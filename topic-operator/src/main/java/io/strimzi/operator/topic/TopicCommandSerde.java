/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import com.fasterxml.jackson.databind.JsonNode;
import io.apicurio.registry.utils.kafka.SelfSerde;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * TopicCommand Kafka Serde
 */
public class TopicCommandSerde extends SelfSerde<TopicCommand> {

    private static final String UUID = "uuid";
    private static final String TYPE = "type";
    private static final String TOPIC = "topic";
    private static final String KEY = "key";

    @Override
    public byte[] serialize(String topic, TopicCommand data) {
        return TopicSerialization.toBytes((mapper, root) -> {
            root.put(UUID, data.getUuid());
            TopicCommand.Type type = data.getType();
            root.put(TYPE, type.ordinal());
            if (type == TopicCommand.Type.CREATE || type == TopicCommand.Type.UPDATE) {
                byte[] json = TopicSerialization.toJson(data.getTopic());
                root.put(TOPIC, json);
            } else {
                root.put(KEY, data.getKey());
            }
        });
    }

    @Override
    public TopicCommand deserialize(String t, byte[] data) {
        return TopicSerialization.fromJson(data, (mapper, bytes) -> {
            try {
                JsonNode root = mapper.readTree(bytes);
                String uuid = root.get(UUID).asText();
                int ordinal = root.get(TYPE).asInt();
                TopicCommand.Type type = TopicCommand.Type.values()[ordinal];
                Topic topic = null;
                TopicName name = null;
                if (type == TopicCommand.Type.CREATE || type == TopicCommand.Type.UPDATE) {
                    byte[] json = root.get(TOPIC).binaryValue();
                    topic = TopicSerialization.fromJson(json);
                } else {
                    name = new TopicName(root.get(KEY).asText());
                }
                return new TopicCommand(uuid, type, topic, name);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
