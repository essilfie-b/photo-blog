package spring.cloud.services.impl;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import spring.cloud.config.S3Config;
import spring.cloud.dtos.images.S3EventMessage;
import spring.cloud.dtos.images.WatermarkRequest;
import spring.cloud.entities.Image;
import spring.cloud.repositories.ImageRepository;
import spring.cloud.services.*;

@Service
@AllArgsConstructor
@Slf4j
public class ImageProcessingServiceImpl implements ImageProcessingService {
    private final ImageKitService imageKitService;
    private final S3Service s3Service;
    private final SQSService sqsService;
    private final S3Config s3Config;
    private final CognitoService cognitoService;
    private final ImageRepository imageRepository;


    @Override
    public void processImageFromS3Event(S3EventMessage s3EventMessage) {
        try {
            var user = cognitoService.getCurrentUser();

            var watermarkRequest = extractWatermarkRequestFromKey(s3EventMessage.objectKey());

            var imageUrl = String.format("https://%s.s3.%s.amazonaws.com/%s",
                    s3EventMessage.bucketName(),
                    s3Config.region(),
                    s3EventMessage.objectKey());

            var watermarkedImageUrl = imageKitService.addWatermark(imageUrl, watermarkRequest);
            var processedImageUrl = s3Service.uploadImage(watermarkedImageUrl);

            var image = Image.builder()
                    .user(user)
                    .url(processedImageUrl)
                    .build();

            imageRepository.save(image);
            log.info("ImageProcessingService::processImageFromS3Event: Image metadata saved to database");
        } catch (Exception e) {
            log.error("ImageProcessingService::processImageFromS3Event: Failed to process image: {}", s3EventMessage.objectKey(), e);

            try {
                sqsService.sendRetryMessage(s3EventMessage);
                log.info("ImageProcessingService::processImageFromS3Event: Sent to retry queue");
            } catch (Exception retryException) {
                log.error("ImageProcessingService::processImageFromS3Event: Failed to send to retry queue", retryException);
            }

            throw e;
        }


    }

    private WatermarkRequest extractWatermarkRequestFromKey(String objectKey) {
        var parts = objectKey.split("_");
        if (parts.length >= 6) {
            var text = parts[3];
            var position = parts[4];
            var color = parts[5];
            var fontSizePart = parts[6].split("\\.")[0];
            var fontSize = Integer.parseInt(fontSizePart);

            return new WatermarkRequest(text, position, color, fontSize);
        }

        return new WatermarkRequest("Watermark", "center", "e50000", 24);
    }
}
