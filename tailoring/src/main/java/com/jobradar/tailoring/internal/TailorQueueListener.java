package com.jobradar.tailoring.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jobradar.tailoring.CvReady;
import com.jobradar.tailoring.TailoringService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes tailoring requests: the hot queue on its own thread, the backlog queue with one
 * low-priority consumer. A finished CV is announced on the cv-ready queue.
 *
 * <p>A request is deleted only after its CV is stored and announced; failures are redelivered
 * and parked in the dead-letter queue after 3 receives.
 */
@Component
@ConditionalOnProperty(name = "jobradar.tailoring.listener.enabled", havingValue = "true", matchIfMissing = true)
public class TailorQueueListener {

    private static final Logger log = LoggerFactory.getLogger(TailorQueueListener.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Request(long userId, long postingId) {}

    private final SqsClient sqs;
    private final JsonMapper json;
    private final TailoringService tailoring;
    private final TailoringProperties properties;

    TailorQueueListener(SqsClient sqs, JsonMapper json, TailoringService tailoring, TailoringProperties properties) {
        this.sqs = sqs;
        this.json = json;
        this.tailoring = tailoring;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 100)
    void pollHot() {
        poll(properties.queues().tailorHotUrl());
    }

    @Scheduled(fixedDelay = 5_000)
    void pollBacklog() {
        poll(properties.queues().tailorBacklogUrl());
    }

    /** One message at a time: rendering is CPU-heavy and each request runs Typst several times. */
    int poll(String queueUrl) {
        int handled = 0;
        for (Message message : sqs.receiveMessage(
                        request -> request.queueUrl(queueUrl).maxNumberOfMessages(1).waitTimeSeconds(10))
                .messages()) {
            try {
                Request request = json.readValue(message.body(), Request.class);
                Optional<CvReady> ready = tailoring.tailor(request.userId(), request.postingId());
                ready.ifPresent(cv -> sqs.sendMessage(send ->
                        send.queueUrl(properties.queues().cvReadyUrl()).messageBody(json.writeValueAsString(cv))));
                sqs.deleteMessage(delete -> delete.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
                handled++;
            } catch (RuntimeException e) {
                log.warn("Failed to handle tailoring request {}", message.messageId(), e);
            }
        }
        return handled;
    }
}
