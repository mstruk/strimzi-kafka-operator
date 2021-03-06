/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.fabric8.kubernetes.api.model.Event;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;

import java.util.List;

public interface K8s {

    /**
     * Asynchronously create the given resource.
     * @param topicResource The resource to be created.
     * @return A future which completes when the topic has been created.
     */
    Future<Void> createResource(KafkaTopic topicResource);

    /**
     * Asynchronously update the given resource.
     * @param topicResource The topic.
     * @return A future which completes when the topic has been updated.
     */
    Future<Void> updateResource(KafkaTopic topicResource);

    /**
     * Asynchronously delete the given resource.
     * @param resourceName The name of the resource to be deleted.
     * @return A future which completes when the topic has been deleted.
     */
    Future<Void> deleteResource(ResourceName resourceName);

    /**
     * Asynchronously list the resources.
     * @return A future which completes with the topics.
     */
    Future<List<KafkaTopic>> listResources();

    /**
     * Get the resource with the given name, invoking the given handler with the result.
     * If a resource with the given name does not exist, the handler will be called with
     * a null {@link AsyncResult#result() result()}.
     * @param resourceName The name of the resource to get.
     * @return A future which completes with the topic
     */
    Future<KafkaTopic> getFromName(ResourceName resourceName);

    /**
     * Create an event.
     * @param event The event.
     * @return A future which completes when the event has been created.
     */
    Future<Void> createEvent(Event event);
}
