package spring.cloud.controllers;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import spring.cloud.dtos.images.ImageResponse;
import spring.cloud.dtos.images.WatermarkRequest;
import spring.cloud.dtos.images.WatermarkResponse;
import spring.cloud.services.ImageService;
import spring.cloud.services.S3Service;

@RestController
@RequestMapping("/api/images")
@AllArgsConstructor
@Slf4j
public class ImageController {

    private final S3Service s3Service;
    private final ImageService imageService;

    @PostMapping("/upload")
    public ResponseEntity<WatermarkResponse> addWatermark(
            @RequestParam("file") MultipartFile file,
            @RequestParam("text") String text,
            @RequestParam("position") String position,
            @RequestParam("color") String color,
            @RequestParam("fontSize") Integer fontSize
    ) {

        var request = new WatermarkRequest(text, position, color, fontSize);

        var imageUrl = s3Service.uploadImage(file, request);
        log.info("ImageController::uploadImage: File uploaded to staging: {}", imageUrl);

        return ResponseEntity.ok(new WatermarkResponse("Image uploaded successfully. Processing started.", true));
    }

    @GetMapping
    public ResponseEntity<Page<ImageResponse>> getUserActiveImages(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        page = Math.max(page, 0);
        size = Math.min(size, 50);
        return ResponseEntity.ok(imageService.getUserActiveImages(page, size));
    }

    @GetMapping("/recycle-bin")
    public ResponseEntity<Page<ImageResponse>> getUserRecycleBinImages(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        page = Math.max(page, 0);
        size = Math.min(size, 50);
        return ResponseEntity.ok(imageService.getRecycleBinImages(page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ImageResponse> getImageById(@PathVariable("id") Long id) {
        return ResponseEntity.ok(imageService.getImageById(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteImage(@PathVariable Long id) {
        imageService.softDeleteImage(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Void> restoreImage(@PathVariable Long id) {
        imageService.restoreImage(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/permanent")
    public ResponseEntity<Void> permanentlyDeleteImage(@PathVariable Long id) {
        imageService.permanentlyDeleteImage(id);
        return ResponseEntity.noContent().build();
    }
}
