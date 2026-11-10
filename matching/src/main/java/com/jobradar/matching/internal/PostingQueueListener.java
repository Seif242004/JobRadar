package com.jobradar.matching.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes the hot and the backlog queue on separate scheduler threads, so a backlog flood can
 * never delay a fresh posting.
 *
 * <p>A message is deleted only after it was handled. A failing message becomes visible again and
 * moves to the dead-letter queue after 3 receives (the queue's redrive policy).
 */
@Component
@EnableConfigurationProperties(MatchingProperties.class)
@ConditionalOnProperty(name = "jobradar.matching.listener.enabled", havingValue = "true", matchIfMissing = true)
public class PostingQueueListener {

    private static final Logger log = LoggerFactory.getLogger(PostingQueueListener.class);

    /** Consumer-side shape of the message; extra fields the producer adds are ignored. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record PostingIngestedMessage(long postingId) {}

    private final SqsClient sqs;
    private final JsonMapper json;
    private final PostingMatcher matcher;
    private final MatchingProperties properties;

    PostingQueueListener(SqsClient sqs, JsonMapper json, PostingMatcher matcher, MatchingProperties properties) {
        this.sqs = sqs;
        this.json = json;
        this.matcher = matcher;
        this.properties = properties;
    }

    /** Hot queue: long-polls back to back, so a fresh posting is picked up within seconds. */
    @Scheduled(fixedDelay = 100)
    void pollHot() {
        poll(properties.queues().hotUrl(), 10);
    }

    /** Backlog queue: one low-priority consumer, small batches, a pause between polls. */
    @Scheduled(fixedDelay = 5_000)
    void pollBacklog() {
        poll(properties.queues().backlogUrl(), 5);
    }

    /** Receives up to {@code maxMessages}, handles each, and deletes the ones that succeeded. */
    int poll(String queueUrl, int maxMessages) {
        int handled = 0;
        for (Message message : sqs.receiveMessage(
                        request -> request.queueUrl(queueUrl).maxNumberOfMessages(maxMessages).waitTimeSeconds(10))
                .messages()) {
            try {
                matcher.match(json.readValue(message.body(), PostingIngestedMessage.class).postingId());
                sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
                handled++;
            } catch (RuntimeException e) {
                // Not deleted: SQS redelivers it, and the redrive policy parks it after 3 attempts.
                log.warn("Failed to handle queue message {}", message.messageId(), e);
            }
        }
        return handled;
    }
}
