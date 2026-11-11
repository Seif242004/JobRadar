#!/bin/sh
# Creates the local equivalents of the AWS resources the pipeline uses.
set -eu

# Dead-letter queue first, so the two work queues can point their redrive policy at it.
awslocal sqs create-queue --queue-name jobradar-dlq
DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url "$(awslocal sqs get-queue-url --queue-name jobradar-dlq --query QueueUrl --output text)" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)

# After 3 failed receives a message moves to the DLQ instead of blocking the queue.
REDRIVE="{\"deadLetterTargetArn\":\"$DLQ_ARN\",\"maxReceiveCount\":\"3\"}"
printf '{"RedrivePolicy":"%s"}' "$(printf '%s' "$REDRIVE" | sed 's/"/\\"/g')" > /tmp/queue-attrs.json

# Hot queue: postings under 2 hours old. Backlog queue: everything else.
awslocal sqs create-queue --queue-name jobradar-hot     --attributes file:///tmp/queue-attrs.json
awslocal sqs create-queue --queue-name jobradar-backlog --attributes file:///tmp/queue-attrs.json

# Tailoring (Flow 3): requests from matching, results to the notifier. Hot requests are
# the ones for postings that qualify for an Apply-now push.
awslocal sqs create-queue --queue-name jobradar-tailor-hot     --attributes file:///tmp/queue-attrs.json
awslocal sqs create-queue --queue-name jobradar-tailor-backlog --attributes file:///tmp/queue-attrs.json
awslocal sqs create-queue --queue-name jobradar-cv-ready       --attributes file:///tmp/queue-attrs.json

# One bucket; raw postings under raw/, generated PDFs under cv/.
awslocal s3 mb s3://jobradar-local
