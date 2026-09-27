package spring.cloud.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import spring.cloud.config.S3Config;
import spring.cloud.dtos.images.WatermarkRequest;
import spring.cloud.entities.Image;
import spring.cloud.exceptions.InvalidOperationException;
import spring.cloud.repositories.ImageRepository;
import spring.cloud.services.CognitoService;
import spring.cloud.services.S3Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3ServiceImpl implements S3Service {
    private final S3Client s3Client;
    private final S3Config s3Config;
    private final CognitoService cognitoService;
    private final ImageRepository imageRepository;

    public static final String AMAZONAWS_DOMAIN_SUFFIX = ".amazonaws.com/";

    @Override
    public String uploadImage(MultipartFile file, WatermarkRequest request) {
        var imageKey = generateImageKeyWithMetadata(file, request);
        return uploadImageToS3Staging(imageKey, file);
    }

    @Override
    public String uploadImage(String imageUrl) {
        try {

            var client = HttpClient.newHttpClient();

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200)
                throw new InvalidOperationException("Failed to download image. HTTP status: " + response.statusCode());

            try (InputStream inputStream = response.body()) {
                var contentType = response.headers()
                        .firstValue("content-type")
                        .orElse("application/octet-stream");

                var contentLength = response.headers()
                        .firstValue("content-length")
                        .map(Long::parseLong)
                        .orElse(-1L);

                String imageKey = generateImageKeyFromUrl(imageUrl);
                var user = cognitoService.getCurrentUser();

                var objectUrl = uploadImageToS3(imageKey, inputStream, contentType, contentLength);
                var image = Image.builder()
                        .url(objectUrl)
                        .user(user)
                        .build();

                log.info(user.getEmail());

                imageRepository.save(image);

                return objectUrl;
            }

        } catch (IOException e) {
            throw new InvalidOperationException("Failed to download image from URL: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvalidOperationException("Image download was interrupted: " + e.getMessage());
        } catch (S3Exception e) {
            throw new InvalidOperationException("S3 upload failed: " + e.getMessage());
        }
    }

    @Override
    public void softDeleteImage(String imageUrl) {
        String key = extractKeyFromUrl(imageUrl);
        String bucketName = getBucketNameFromUrl(imageUrl);

        var deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        s3Client.deleteObject(deleteRequest);
    }

    @Override
    public void restoreImage(String imageUrl) {
        try {
            String key = extractKeyFromUrl(imageUrl);
            String bucketName = getBucketNameFromUrl(imageUrl);

            removeDeleteMarker(bucketName, key);
        } catch (S3Exception e) {
            throw new InvalidOperationException("Failed to restore image in S3: " + e.getMessage());
        }
    }

    @Override
    public void permanentDeleteImage(String imageUrl) {
        try {
            var key = extractKeyFromUrl(imageUrl);
            var bucketName = getBucketNameFromUrl(imageUrl);

            deleteAllVersions(bucketName, key);
        } catch (S3Exception e) {
            throw new InvalidOperationException("Failed to restore image in S3: " + e.getMessage());
        }
    }

    @Override
    public byte[] getObjectFromStaging(String objectKey) {
        try {
            var getObjectRequest = GetObjectRequest.builder()
                    .bucket(s3Config.stagingBucketName())
                    .key(objectKey)
                    .build();

            var response = s3Client.getObject(getObjectRequest);
            return response.readAllBytes();

        } catch (Exception e) {
            throw new InvalidOperationException("Failed to get object from staging: " + e.getMessage());
        }
    }

    private void deleteAllVersions(String bucketName, String key) throws S3Exception {
        var listRequest = ListObjectVersionsRequest.builder()
                .bucket(bucketName)
                .prefix(key)
                .build();

        var versions = s3Client.listObjectVersions(listRequest);

        versions.versions().forEach(version -> {
            if (version.key().equals(key)) {
                var deleteRequest = DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .versionId(version.versionId())
                        .build();

                s3Client.deleteObject(deleteRequest);
            }
        });

        versions.deleteMarkers().forEach(deleteMarker -> {
            if (deleteMarker.key().equals(key)) {
                var deleteRequest = DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .versionId(deleteMarker.versionId())
                        .build();

                s3Client.deleteObject(deleteRequest);
            }
        });
    }

    private void removeDeleteMarker(String bucketName, String key) throws S3Exception {
        var listRequest = ListObjectVersionsRequest.builder()
                .bucket(bucketName)
                .prefix(key)
                .build();

        var versions = s3Client.listObjectVersions(listRequest);

        versions.deleteMarkers().stream()
                .filter(marker -> marker.key().equals(key))
                .findFirst()
                .ifPresent(deleteMarker -> {
                    var deleteMarkerRequest = DeleteObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .versionId(deleteMarker.versionId())
                            .build();

                    s3Client.deleteObject(deleteMarkerRequest);
                });
    }

    private String getBucketNameFromUrl(String url) {
        if (url.contains(".s3.") && url.contains(".amazonaws.com")) {
            String domain = url.substring(url.indexOf("://") + 3);
            return domain.substring(0, domain.indexOf(".s3."));
        } else if (url.contains("s3.") && url.contains(AMAZONAWS_DOMAIN_SUFFIX)) {
            String afterDomain = url.substring(url.indexOf(AMAZONAWS_DOMAIN_SUFFIX) + 15);
            return afterDomain.substring(0, afterDomain.indexOf("/"));
        }
        throw new InvalidOperationException("Unrecognized S3 URL format");
    }

    private String extractKeyFromUrl(String url) {
        String urlSubstring = url.substring(url.indexOf(AMAZONAWS_DOMAIN_SUFFIX) + 15);
        if (url.contains(".s3.") && url.contains(AMAZONAWS_DOMAIN_SUFFIX)) {
            return urlSubstring;
        } else if (url.contains("s3.") && url.contains(AMAZONAWS_DOMAIN_SUFFIX)) {
            return urlSubstring.substring(urlSubstring.indexOf("/") + 1);
        }
        throw new InvalidOperationException("Unrecognized S3 URL format");
    }


    private String uploadImageToS3(String imageKey, InputStream inputStream, String contentType, long contentLength) {
        try {
            var putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3Config.processedBucketName())
                    .key(imageKey)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build();

            RequestBody requestBody;
            if (contentLength > 0)
                requestBody = RequestBody.fromInputStream(inputStream, contentLength);
            else {
                byte[] bytes = inputStream.readAllBytes();
                requestBody = RequestBody.fromBytes(bytes);
            }

            s3Client.putObject(putObjectRequest, requestBody);

            return String.format("https://%s.s3.%s.amazonaws.com/%s",
                    s3Config.processedBucketName(), s3Config.region(), imageKey);
        } catch (IOException e) {
            throw new InvalidOperationException("Failed to read image data: " + e.getMessage());
        } catch (S3Exception e) {
            throw new InvalidOperationException("S3 upload failed: " + e.getMessage());
        }
    }

    private String generateImageKeyFromUrl(String imageUrl) {
        try {
            var fileName = imageUrl.substring(imageUrl.lastIndexOf("/") + 1);
            var extension = "";

            if (fileName.contains(".")) {
                extension = fileName.substring(fileName.lastIndexOf(".") + 1);
            }

            return String.format("images/processed_%d_%s%s",
                    System.currentTimeMillis(),
                    UUID.randomUUID().toString().substring(0, 8),
                    extension);
        } catch (Exception e) {
            return String.format("images/unprocessed_%d_%s",
                    System.currentTimeMillis(),
                    UUID.randomUUID().toString().substring(0, 8)); // Fallback if URL parsing fails
        }
    }

    private String generateImageKeyWithMetadata(MultipartFile file, WatermarkRequest request) {
        var fileName = file.getOriginalFilename();
        var extension = "";

        if (fileName != null && fileName.contains("."))
            extension = fileName.substring(fileName.lastIndexOf("."));

        return String.format("images/unprocessed_%d_%s_%s_%s_%s_%d%s",
                System.currentTimeMillis(),
                UUID.randomUUID().toString().substring(0, 8),
                request.text().replaceAll("[^a-zA-Z0-9]", ""),
                request.position(),
                request.color(),
                request.fontSize(),
                extension);
    }

    private String uploadImageToS3Staging(String key, MultipartFile file) {
        try {
            var putObjectRequest = PutObjectRequest.builder()
                    .bucket(s3Config.stagingBucketName())
                    .key(key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            s3Client.putObject(putObjectRequest,
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            return String.format("https://%s.s3.%s.amazonaws.com/%s",
                    s3Config.stagingBucketName(), s3Config.region(), key);

        } catch (IOException e) {
            throw new InvalidOperationException("Failed to read uploaded file: " + e.getMessage());
        } catch (S3Exception e) {
            throw new InvalidOperationException("S3 upload failed: " + e.getMessage());
        }
    }
}