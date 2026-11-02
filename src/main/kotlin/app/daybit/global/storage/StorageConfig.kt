package app.daybit.global.storage

import app.daybit.global.config.DaybitProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.nio.file.Path

@Configuration
class StorageConfig(private val props: DaybitProperties) {

    @Bean
    fun fileStorage(): FileStorage {
        val storage = props.storage
        return when (storage.type) {
            DaybitProperties.StorageType.local -> LocalFileStorage(
                root = Path.of(storage.localDir).toAbsolutePath().normalize(),
                publicBaseUrl = props.publicBaseUrl,
                secret = props.jwt.secret,
            )
            DaybitProperties.StorageType.s3 -> {
                val s3 = storage.s3
                val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.accessKey, s3.secretKey))
                val endpoint = URI.create(s3.endpoint)
                // R2 는 리전이 auto 이고, 버킷 이름을 경로에 넣는 방식을 써요
                val config = S3Configuration.builder().pathStyleAccessEnabled(true).build()
                val client = S3Client.builder().endpointOverride(endpoint).region(Region.of("auto"))
                    .credentialsProvider(credentials).serviceConfiguration(config).build()
                val presigner = S3Presigner.builder().endpointOverride(endpoint).region(Region.of("auto"))
                    .credentialsProvider(credentials).serviceConfiguration(config).build()
                S3FileStorage(client, presigner, s3.bucket)
            }
        }
    }
}
