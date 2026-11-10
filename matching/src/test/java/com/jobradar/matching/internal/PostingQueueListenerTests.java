package com.jobradar.matching.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.json.JsonMapper;

/** A message is deleted only after it was handled, so failures are redelivered. */
class PostingQueueListenerTests {

    private final SqsClient sqs = mock(SqsClient.class);
    private final PostingMatcher matcher = mock(PostingMatcher.class);
    private final PostingQueueListener listener = new PostingQueueListener(
            sqs, JsonMapper.builder().build(), matcher, new MatchingProperties(new MatchingProperties.Queues("hot", "backlog")));

    @SuppressWarnings("unchecked")
    private void queueHolds(String... bodies) {
        Message[] messages = new Message[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            messages[i] = Message.builder().messageId("m" + i).receiptHandle("r" + i).body(bodies[i]).build();
        }
        when(sqs.receiveMessage(any(Consumer.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(messages).build());
    }

    @Test
    @SuppressWarnings("unchecked")
    void handledMessagesAreDeletedAndTheProducersExtraFieldsAreIgnored() {
        queueHolds("{\"postingId\":7,\"firstSeenAt\":\"2026-11-10T12:00:00Z\",\"postedAt\":null}");

        assertThat(listener.poll("hot", 10)).isEqualTo(1);

        verify(matcher).match(7L);
        verify(sqs).deleteMessage(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailingMessageIsLeftOnTheQueueAndDoesNotBlockTheOthers() {
        queueHolds("{\"postingId\":1}", "not json", "{\"postingId\":3}");
        when(matcher.match(1L)).thenThrow(new IllegalStateException("database down"));

        assertThat(listener.poll("backlog", 5)).isEqualTo(1);

        verify(matcher).match(3L);
        // Only posting 3 was deleted; 1 and the malformed message will be redelivered.
        verify(sqs, times(1)).deleteMessage(any(Consumer.class));
    }
}
