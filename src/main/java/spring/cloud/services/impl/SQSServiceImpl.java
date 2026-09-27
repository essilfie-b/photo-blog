package spring.cloud.services.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import spring.cloud.dtos.images.S3EventMessage;
import spring.cloud.exceptions.InvalidOperationException;
import spring.cloud.services.SQSService;

@Service
@RequiredArgsConstructor
@Slf4j
public class SQSServiceImpl  implements SQSService {
    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;

    @Value("${aws.sqs.retry-queue-url}")
    private String retryQueueUrl;

    @Override
    public void sendRetryMessage(S3EventMessage event) {
        try {
            var messageBody = objectMapper.writeValueAsString(event);

            var sendMessageRequest = SendMessageRequest.builder()
                    .queueUrl(retryQueueUrl)
                    .messageBody(messageBody)
                    .build();

            var response = sqsClient.sendMessage(sendMessageRequest);

            log.info("SQSService::sendRetryMessage: Retry message sent with ID: {}", response.messageId());
        } catch (JsonProcessingException e) {
            log.error("SQSService::sendRetryMessage: Failed to send retry message", e);
            throw new InvalidOperationException("Failed to send retry message: " + e.getMessage());
        }
    }
}
