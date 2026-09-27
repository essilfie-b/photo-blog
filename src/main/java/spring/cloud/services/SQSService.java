package spring.cloud.services;

import spring.cloud.dtos.images.S3EventMessage;

public interface SQSService {
    void sendRetryMessage(S3EventMessage s3EventMessage);
}
