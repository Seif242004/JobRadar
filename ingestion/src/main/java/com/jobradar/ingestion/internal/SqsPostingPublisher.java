package com.jobradar.ingestion.internal;

import com.jobradar.ingestion.PostingIngested;
import com.jobradar.ingestion.PostingPublisher;
import com.jobradar.ingestion.PostingQueue;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

/** Sends {@link PostingIngested} as JSON to the hot or the backlog SQS queue. */
@Component
class SqsPostingPublisher implements PostingPublisher {

    private final SqsClient sqs;
    private final JsonMapper json;
    private final IngestionProperties properties;

    SqsPostingPublisher(SqsClient sqs, JsonMapper json, IngestionProperties properties) {
        this.sqs = sqs;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public void publish(PostingIngested event, PostingQueue queue) {
        String queueUrl = queue == PostingQueue.HOT
                ? properties.queues().hotUrl()
                : properties.queues().backlogUrl();
        sqs.sendMessage(request -> request.queueUrl(queueUrl).messageBody(json.writeValueAsString(event)));
    }
}
