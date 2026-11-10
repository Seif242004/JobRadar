package com.jobradar.notifier.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code CvReady} messages from the tailoring-worker. A message is deleted after it was
 * handled, including when the posting no longer qualifies for a push; a failure is redelivered
 * and parked in the dead-letter queue after 3 receives.
 */
@Component
@ConditionalOnProperty(name = "jobradar.notifier.listener.enabled", havingValue = "true", matchIfMissing = true)
public class CvReadyQueueListener {

    private static final Logger log = LoggerFactory.getLogger(CvReadyQueueListener.class);

    /** Consumer-side shape of the message; extra fields the producer adds are ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CvReadyMessage(long userId, long postingId, long cvVersionId, double atsScore) {}

    private final SqsClient sqs;
    private final JsonMapper json;
    private final AlertService alerts;
    private final NotifierProperties properties;

    CvReadyQueueListener(SqsClient sqs, JsonMapper json, AlertService alerts, NotifierProperties properties) {
        this.sqs = sqs;
        this.json = json;
        this.alerts = alerts;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 100)
    void poll() {
        String queueUrl = properties.queues().cvReadyUrl();
        for (Message message : sqs.receiveMessage(
                        request -> request.queueUrl(queueUrl).maxNumberOfMessages(5).waitTimeSeconds(10))
                .messages()) {
            try {
                CvReadyMessage ready = json.readValue(message.body(), CvReadyMessage.class);
                alerts.onCvReady(ready.userId(), ready.postingId(), ready.cvVersionId(), ready.atsScore());
                sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
            } catch (RuntimeException e) {
                log.warn("Failed to handle CV-ready message {}", message.messageId(), e);
            }
        }
    }
}
