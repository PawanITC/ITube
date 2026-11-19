package ITubeBackend.storage;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Blob storage for video sources, renditions and thumbnails. Postgres only keeps the object keys.
 * Implementations: {@link S3ObjectStorage} (AWS S3, SeaweedFS, any S3-compatible store) and
 * {@link LocalObjectStorage} (a folder on disk, for tests and the h2 profile).
 * Failures surface as {@link StorageException}.
 */
public interface ObjectStorage {

    /** Streams {@code length} bytes into {@code key}, replacing anything already there. */
    void put(String key, InputStream content, long length, String contentType);

    default void put(String key, byte[] content, String contentType) {
        put(key, new java.io.ByteArrayInputStream(content), content.length, contentType);
    }

    Optional<ObjectInfo> stat(String key);

    /** Reads at most {@code length} bytes starting at {@code offset}. */
    byte[] readRange(String key, long offset, int length);

    /** Copies the whole object to {@code target}, which must not exist yet. */
    void download(String key, Path target);

    /**
     * A time-limited URL the browser can fetch directly (so video bytes never pass through the
     * backend), or empty if this store can't issue one; the caller then streams the bytes itself.
     */
    Optional<URI> presignedGet(String key, Duration ttl);

    /** Deleting a key that doesn't exist is not an error. */
    void delete(String key);

    /**
     * Marks an object as publicly readable (tag {@code public=true}) or not. The media bucket's policy lets the CDN
     * read only objects tagged public, so a members-only or private video can never be fetched through the CDN even
     * if its key leaks. Stores without tags (local folder) ignore this.
     */
    default void setPublicRead(String key, boolean publicRead) {
    }

    // --- Multipart uploads: a large file is sent as numbered parts, possibly over several days ---

    /** Starts a multipart upload into {@code key} and returns its upload id. */
    String createMultipartUpload(String key, String contentType);

    /**
     * A time-limited URL the browser can PUT one part to directly, or empty if this store can't issue
     * one; the browser then sends the part through the backend ({@link #uploadPart}).
     */
    Optional<URI> presignedUploadPart(String key, String uploadId, int partNumber, Duration ttl);

    /** Stores one part (replacing an earlier copy of the same part) and returns its ETag. */
    String uploadPart(String key, String uploadId, int partNumber, InputStream content, long length);

    /**
     * Parts stored so far, by part number. The store is the source of truth, so a browser that crashed
     * mid-part simply re-sends it. Throws {@link MultipartUploadNotFoundException} if the upload is gone.
     */
    List<UploadedPart> listParts(String key, String uploadId);

    /** Joins the parts (in part-number order) into the object at {@code key}. */
    void completeMultipartUpload(String key, String uploadId, List<UploadedPart> parts);

    /** Discards the upload and its parts. Aborting an unknown upload is not an error. */
    void abortMultipartUpload(String key, String uploadId);

    record ObjectInfo(long length, String contentType) {
    }

    record UploadedPart(int partNumber, String etag, long size) {
    }
}
