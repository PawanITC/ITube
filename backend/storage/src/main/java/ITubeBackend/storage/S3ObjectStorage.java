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
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class S3ObjectStorage implements ObjectStorage, AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(S3ObjectStorage.class);
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

        if (properties.createBucket()) {
            ensureBucket();
            allowBrowserUploads(properties.corsAllowedOrigins());
        }
    }

    /**
     * Browsers PUT upload parts straight to the bucket, which needs CORS. Only done for buckets the backend
     * creates itself (local SeaweedFS); Terraform sets CORS on the AWS bucket.
     */
    private void allowBrowserUploads(String origins) {
        if (isBlank(origins)) return;
        List<String> allowed = Arrays.stream(origins.split(",")).map(String::trim).filter(o -> !o.isEmpty()).toList();
        try {
            client.putBucketCors(b -> b.bucket(bucket).corsConfiguration(c -> c.corsRules(CORSRule.builder()
                    .allowedOrigins(allowed).allowedMethods("GET", "HEAD", "PUT").allowedHeaders("*")
                    .exposeHeaders("ETag").maxAgeSeconds(3600).build())));
        } catch (SdkException exception) {
            log.warn("Could not set CORS on bucket {}; browser uploads may be blocked", bucket, exception);
        }
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
    public void setPublicRead(String key, boolean publicRead) {
        try {
            client.putObjectTagging(b -> b.bucket(bucket).key(key)
                    .tagging(t -> t.tagSet(tag -> tag.key("public").value(Boolean.toString(publicRead)))));
        } catch (SdkException exception) {
            throw new StorageException("Could not tag " + key, exception);
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
    public String createMultipartUpload(String key, String contentType) {
        try {
            return client.createMultipartUpload(b -> b.bucket(bucket).key(key).contentType(contentType)).uploadId();
        } catch (SdkException exception) {
            throw new StorageException("Could not start an upload to " + key, exception);
        }
    }

    @Override
    public Optional<URI> presignedUploadPart(String key, String uploadId, int partNumber, Duration ttl) {
        try {
            return Optional.of(presigner.presignUploadPart(p -> p.signatureDuration(ttl)
                    .uploadPartRequest(u -> u.bucket(bucket).key(key).uploadId(uploadId).partNumber(partNumber)))
                    .url().toURI());
        } catch (URISyntaxException exception) {
            throw new StorageException("Invalid pre-signed URL for " + key, exception);
        }
    }

    @Override
    public String uploadPart(String key, String uploadId, int partNumber, InputStream content, long length) {
        try {
            return client.uploadPart(b -> b.bucket(bucket).key(key).uploadId(uploadId).partNumber(partNumber)
                    .contentLength(length), RequestBody.fromInputStream(content, length)).eTag();
        } catch (NoSuchUploadException missing) {
            throw new MultipartUploadNotFoundException("No upload " + uploadId + " for " + key, missing);
        } catch (SdkException exception) {
            throw new StorageException("Could not store part " + partNumber + " of " + key, exception);
        }
    }

    @Override
    public List<UploadedPart> listParts(String key, String uploadId) {
        try {
            return client.listPartsPaginator(b -> b.bucket(bucket).key(key).uploadId(uploadId)).parts().stream()
                    .map(part -> new UploadedPart(part.partNumber(), part.eTag(), part.size()))
                    .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                    .toList();
        } catch (NoSuchUploadException missing) {
            throw new MultipartUploadNotFoundException("No upload " + uploadId + " for " + key, missing);
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new MultipartUploadNotFoundException("No upload " + uploadId + " for " + key, exception);
            }
            throw new StorageException("Could not list the parts of " + key, exception);
        } catch (SdkException exception) {
            throw new StorageException("Could not list the parts of " + key, exception);
        }
    }

    @Override
    public void completeMultipartUpload(String key, String uploadId, List<UploadedPart> parts) {
        List<CompletedPart> completed = parts.stream()
                .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                .map(part -> CompletedPart.builder().partNumber(part.partNumber()).eTag(part.etag()).build())
                .toList();
        try {
            client.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(m -> m.parts(completed)));
        } catch (NoSuchUploadException missing) {
            throw new MultipartUploadNotFoundException("No upload " + uploadId + " for " + key, missing);
        } catch (SdkException exception) {
            throw new StorageException("Could not complete the upload of " + key, exception);
        }
    }

    @Override
    public void abortMultipartUpload(String key, String uploadId) {
        try {
            client.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId));
        } catch (NoSuchUploadException missing) {
            // Already gone
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) throw new StorageException("Could not abort the upload of " + key, exception);
        } catch (SdkException exception) {
            throw new StorageException("Could not abort the upload of " + key, exception);
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
