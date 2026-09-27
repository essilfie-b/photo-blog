package spring.cloud.config;

import io.imagekit.sdk.ImageKit;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@AllArgsConstructor
public class BeansConfig {
    private final S3Config s3Config;
    private final ImagekitConfig imagekitConfig;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(s3Config.region()))
                .build();
    }

    @Bean
    public SqsClient sqsClient() {
        return SqsClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(s3Config.region()))
                .build();
    }

    @Bean
    public ImageKit imageKit() {
        ImageKit imageKit = ImageKit.getInstance();
        io.imagekit.sdk.config.Configuration config = new io.imagekit.sdk.config.Configuration(imagekitConfig.publicKey(), imagekitConfig.privateKey(), imagekitConfig.urlEndpoint());
        imageKit.setConfig(config);

        return imageKit;
    }
}
