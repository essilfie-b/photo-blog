package spring.cloud.services;

import spring.cloud.dtos.images.S3EventMessage;

public interface ImageProcessingService {
    void processImageFromS3Event(S3EventMessage s3EventMessage);
}