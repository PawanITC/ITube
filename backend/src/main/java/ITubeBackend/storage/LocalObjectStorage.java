package ITubeBackend.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

/**
 * Stores objects as files under one folder. For tests and single-machine development only: it can't
 * issue pre-signed URLs, so the backend streams every byte itself.
 */
public class LocalObjectStorage implements ObjectStorage {
    private static final String TYPE_SUFFIX = ".content-type";
    private final Path root;

    public LocalObjectStorage(Path root) {
        try {
            this.root = Files.createDirectories(root).toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new StorageException("Could not create storage folder " + root, exception);
        }
    }

    @Override
    public void put(String key, InputStream content, long length, String contentType) {
        Path file = resolve(key);
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "upload-", ".tmp");
            try (InputStream limited = content) {
                Files.copy(limited, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(typeFile(file), contentType == null ? "application/octet-stream" : contentType);
        } catch (IOException exception) {
            throw new StorageException("Could not store " + key, exception);
        }
    }

    @Override
    public Optional<ObjectInfo> stat(String key) {
        Path file = resolve(key);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            String type = Files.exists(typeFile(file)) ? Files.readString(typeFile(file)) : "application/octet-stream";
            return Optional.of(new ObjectInfo(Files.size(file), type));
        } catch (IOException exception) {
            throw new StorageException("Could not read " + key, exception);
        }
    }

    @Override
    public byte[] readRange(String key, long offset, int length) {
        try (RandomAccessFile file = new RandomAccessFile(resolve(key).toFile(), "r")) {
            long available = Math.max(0, file.length() - offset);
            byte[] bytes = new byte[(int) Math.min(length, available)];
            file.seek(offset);
            file.readFully(bytes);
            return bytes;
        } catch (IOException exception) {
            throw new StorageException("Could not read " + key, exception);
        }
    }

    @Override
    public void download(String key, Path target) {
        try {
            Files.copy(resolve(key), target);
        } catch (IOException exception) {
            throw new StorageException("Could not download " + key, exception);
        }
    }

    @Override
    public Optional<URI> presignedGet(String key, Duration ttl) {
        return Optional.empty();
    }

    @Override
    public void delete(String key) {
        Path file = resolve(key);
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(typeFile(file));
        } catch (IOException exception) {
            throw new StorageException("Could not delete " + key, exception);
        }
    }

    private Path resolve(String key) {
        Path file = root.resolve(key).normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("Invalid storage key: " + key);
        return file;
    }

    private static Path typeFile(Path file) {
        return file.resolveSibling(file.getFileName() + TYPE_SUFFIX);
    }
}
