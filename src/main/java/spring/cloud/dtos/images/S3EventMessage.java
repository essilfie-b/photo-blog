package spring.cloud.dtos.images;

public record S3EventMessage(String bucketName, String objectKey) {}
