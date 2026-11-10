package com.jobradar.matching.internal;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

/** Sends {@code {"userId":..,"postingId":..}} to the hot or the backlog tailoring queue. */
@Component
class SqsTailorRequestPublisher implements TailorRequestPublisher {

    /** Producer-side shape of the message the tailoring-worker consumes. */
    record TailorRequested(long userId, long postingId) {}

    private final SqsClient sqs;
    private final JsonMapper json;
    private final MatchingProperties properties;

    SqsTailorRequestPublisher(SqsClient sqs, JsonMapper json, MatchingProperties properties) {
        this.sqs = sqs;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public void publish(long userId, long postingId, boolean hot) {
        String queueUrl = hot ? properties.queues().tailorHotUrl() : properties.queues().tailorBacklogUrl();
        sqs.sendMessage(request -> request
                .queueUrl(queueUrl)
                .messageBody(json.writeValueAsString(new TailorRequested(userId, postingId))));
    }
}
