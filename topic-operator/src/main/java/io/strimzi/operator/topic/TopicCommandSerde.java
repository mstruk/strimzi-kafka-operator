/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.apicurio.registry.utils.kafka.SelfSerde;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;

/**
 * TopicCommand Kafka Serde
 */
public class TopicCommandSerde extends SelfSerde<TopicCommand> {

    @Override
    public byte[] serialize(String topic, TopicCommand data) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeUTF(data.getUuid());
                TopicCommand.Type type = data.getType();
                oos.writeInt(type.ordinal());
                if (type == TopicCommand.Type.CREATE || type == TopicCommand.Type.UPDATE) {
                    byte[] json = TopicSerialization.toJson(data.getTopic());
                    oos.writeInt(json.length);
                    oos.write(json);
                } else {
                    oos.writeUTF(data.getKey());
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public TopicCommand deserialize(String t, byte[] data) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            try (ObjectInputStream ois = new ObjectInputStream(bais)) {
                String uuid = ois.readUTF();
                int ordinal = ois.readInt();
                TopicCommand.Type type = TopicCommand.Type.values()[ordinal];
                Topic topic = null;
                TopicName name = null;
                if (type == TopicCommand.Type.CREATE || type == TopicCommand.Type.UPDATE) {
                    int length = ois.readInt();
                    byte[] json = new byte[length];
                    ois.read(json, 0, length);
                    topic = TopicSerialization.fromJson(json);
                } else {
                    name = new TopicName(ois.readUTF());
                }
                return new TopicCommand(uuid, type, topic, name);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
