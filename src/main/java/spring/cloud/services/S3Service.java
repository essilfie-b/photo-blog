package spring.cloud.services;

import org.springframework.web.multipart.MultipartFile;
import spring.cloud.dtos.images.WatermarkRequest;

public interface S3Service {
    String uploadImage(MultipartFile file, WatermarkRequest request);

    String uploadImage(String imageUrl);

    void softDeleteImage(String imageUrl);

    void restoreImage(String imageUrl);

    void permanentDeleteImage(String imageUrl);

    byte[] getObjectFromStaging(String objectKey);
}
