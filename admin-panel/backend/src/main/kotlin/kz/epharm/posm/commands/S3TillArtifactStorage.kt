package kz.epharm.posm.commands

import java.net.URI
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * Diagnostics archives in the same MinIO, but in their own private bucket (default `epharm-private`). The media bucket
 * `epharm-receipts` is anonymous-readable through `/s3`, so archives never go there. The bucket is created on the
 * first upload; a new MinIO bucket has no anonymous policy. Old archives are removed by [TillCommandCleanup].
 */
@Component
@Profile("!test")
class S3TillArtifactStorage(
    @Value("\${app.s3.endpoint}") endpoint: String,
    @Value("\${app.s3.region}") private val region: String,
    @Value("\${app.s3.access-key}") accessKey: String,
    @Value("\${app.s3.secret-key}") secretKey: String,
    @Value("\${app.s3.bucket-receipts}") publicBucket: String,
    @Value("\${app.posm.commands.bucket:epharm-private}") private val bucket: String,
) : TillArtifactStorage {
    init {
        require(bucket.isNotBlank() && bucket != publicBucket) {
            "Till diagnostics need a private bucket, never the public media bucket"
        }
    }

    private val log = LoggerFactory.getLogger(S3TillArtifactStorage::class.java)

    private val client: S3Client = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build()

    @Volatile
    private var bucketReady = false

    override fun put(key: String, bytes: ByteArray) {
        require(TillCommandRules.isArchiveKey(key)) { "Unexpected diagnostics archive key" }
        ensureBucket()
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/zip").build(),
            RequestBody.fromBytes(bytes),
        )
    }

    override fun get(key: String): ByteArray? {
        if (!TillCommandRules.isArchiveKey(key)) return null
        return try {
            client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray()
        } catch (ignored: NoSuchKeyException) {
            null
        } catch (ignored: NoSuchBucketException) {
            null
        }
    }

    override fun delete(key: String): Boolean {
        if (!TillCommandRules.isArchiveKey(key)) return true
        return try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
            true
        } catch (ignored: NoSuchBucketException) {
            true
        } catch (error: RuntimeException) {
            log.warn("Till diagnostics archive {} was not deleted: {}", key, error.message)
            false
        }
    }

    private fun ensureBucket() {
        if (bucketReady) return
        synchronized(this) {
            if (bucketReady) return
            val exists = try {
                client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
                true
            } catch (ignored: NoSuchBucketException) {
                false
            } catch (error: S3Exception) {
                if (error.statusCode() == 404) false else throw error
            }
            if (!exists) {
                val request = CreateBucketRequest.builder().bucket(bucket)
                if (region != "us-east-1") {
                    request.createBucketConfiguration(CreateBucketConfiguration.builder().locationConstraint(region).build())
                }
                try {
                    client.createBucket(request.build())
                    log.info("Created private bucket {} for till diagnostics", bucket)
                } catch (ignored: BucketAlreadyOwnedByYouException) {
                    // Another instance created it first.
                } catch (ignored: BucketAlreadyExistsException) {
                    // Same.
                }
            }
            bucketReady = true
        }
    }
}
