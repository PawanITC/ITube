package ITubeBackend.storage;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
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

    record ObjectInfo(long length, String contentType) {
    }
}
