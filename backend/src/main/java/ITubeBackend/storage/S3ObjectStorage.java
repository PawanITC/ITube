package ITubeBackend.storage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public class S3ObjectStorage implements ObjectStorage, AutoCloseable {
    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ObjectStorage(StorageProperties properties) {
        this.bucket = properties.bucket();
        Region region = Region.of(properties.region());
        AwsCredentialsProvider credentials = isBlank(properties.accessKey())
                ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        S3Configuration s3Config = S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyle()).build();

        var clientBuilder = S3Client.builder().region(region).credentialsProvider(credentials).serviceConfiguration(s3Config);
        if (!isBlank(properties.endpoint())) clientBuilder.endpointOverride(URI.create(properties.endpoint()));
        this.client = clientBuilder.build();

        // Pre-signed URLs are opened by the browser, so they must name a host the browser can reach
        String publicEndpoint = isBlank(properties.publicEndpoint()) ? properties.endpoint() : properties.publicEndpoint();
        var presignerBuilder = S3Presigner.builder().region(region).credentialsProvider(credentials).serviceConfiguration(s3Config);
        if (!isBlank(publicEndpoint)) presignerBuilder.endpointOverride(URI.create(publicEndpoint));
        this.presigner = presignerBuilder.build();

        if (properties.createBucket()) ensureBucket();
    }

    private void ensureBucket() {
        try {
            client.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException missing) {
            client.createBucket(b -> b.bucket(bucket));
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) throw new StorageException("Could not check bucket " + bucket, exception);
            client.createBucket(b -> b.bucket(bucket));
        }
    }

    @Override
    public void put(String key, InputStream content, long length, String contentType) {
        try {
            client.putObject(b -> b.bucket(bucket).key(key).contentType(contentType).contentLength(length),
                    RequestBody.fromInputStream(content, length));
        } catch (SdkException exception) {
            throw new StorageException("Could not store " + key, exception);
        }
    }

    @Override
    public Optional<ObjectInfo> stat(String key) {
        try {
            HeadObjectResponse head = client.headObject(b -> b.bucket(bucket).key(key));
            return Optional.of(new ObjectInfo(head.contentLength(), head.contentType()));
        } catch (NoSuchKeyException missing) {
            return Optional.empty();
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) return Optional.empty();
            throw new StorageException("Could not read " + key, exception);
        }
    }

    @Override
    public byte[] readRange(String key, long offset, int length) {
        if (length <= 0) return new byte[0];
        try {
            String range = "bytes=" + offset + "-" + (offset + length - 1);
            return client.getObjectAsBytes(b -> b.bucket(bucket).key(key).range(range)).asByteArray();
        } catch (SdkException exception) {
            throw new StorageException("Could not read " + key, exception);
        }
    }

    @Override
    public void download(String key, Path target) {
        try {
            client.getObject(b -> b.bucket(bucket).key(key), ResponseTransformer.toFile(target));
        } catch (SdkException exception) {
            throw new StorageException("Could not download " + key, exception);
        }
    }

    @Override
    public Optional<URI> presignedGet(String key, Duration ttl) {
        try {
            return Optional.of(presigner.presignGetObject(p -> p.signatureDuration(ttl)
                    .getObjectRequest(g -> g.bucket(bucket).key(key))).url().toURI());
        } catch (URISyntaxException exception) {
            throw new StorageException("Invalid pre-signed URL for " + key, exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(b -> b.bucket(bucket).key(key));
        } catch (SdkException exception) {
            throw new StorageException("Could not delete " + key, exception);
        }
    }

    @Override
    public void close() {
        presigner.close();
        client.close();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
