package ITubeBackend.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Stores objects as files under one folder. For tests and single-machine development only: it can't
 * issue pre-signed URLs, so the backend streams every byte itself.
 */
public class LocalObjectStorage implements ObjectStorage {
    private static final String TYPE_SUFFIX = ".content-type";
    /** Unfinished multipart uploads: {root}/.multipart/{uploadId}/{partNumber}, plus the target key and type. */
    private static final String MULTIPART_DIR = ".multipart";
    private static final String KEY_FILE = "key";
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

    @Override
    public String createMultipartUpload(String key, String contentType) {
        resolve(key);
        String uploadId = UUID.randomUUID().toString();
        Path dir = root.resolve(MULTIPART_DIR).resolve(uploadId);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(KEY_FILE), key);
            Files.writeString(dir.resolve(KEY_FILE + TYPE_SUFFIX), contentType == null ? "application/octet-stream" : contentType);
            return uploadId;
        } catch (IOException exception) {
            throw new StorageException("Could not start an upload to " + key, exception);
        }
    }

    @Override
    public Optional<URI> presignedUploadPart(String key, String uploadId, int partNumber, Duration ttl) {
        return Optional.empty();
    }

    @Override
    public String uploadPart(String key, String uploadId, int partNumber, InputStream content, long length) {
        Path dir = uploadDir(key, uploadId);
        try {
            Path temp = Files.createTempFile(dir, "part-", ".tmp");
            try (InputStream in = content; OutputStream out = Files.newOutputStream(temp)) {
                long copied = in.transferTo(out);
                if (copied != length) {
                    throw new StorageException("Part " + partNumber + " of " + key + " was " + copied + " bytes, expected " + length, null);
                }
            } catch (IOException | RuntimeException exception) {
                Files.deleteIfExists(temp);
                throw exception;
            }
            Files.move(temp, dir.resolve(Integer.toString(partNumber)), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return "\"" + partNumber + "-" + length + "\"";
        } catch (IOException exception) {
            throw new StorageException("Could not store part " + partNumber + " of " + key, exception);
        }
    }

    @Override
    public List<UploadedPart> listParts(String key, String uploadId) {
        Path dir = uploadDir(key, uploadId);
        try (Stream<Path> files = Files.list(dir)) {
            List<UploadedPart> parts = new ArrayList<>();
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!name.chars().allMatch(Character::isDigit)) continue;
                long size = Files.size(file);
                parts.add(new UploadedPart(Integer.parseInt(name), "\"" + name + "-" + size + "\"", size));
            }
            parts.sort(Comparator.comparingInt(UploadedPart::partNumber));
            return parts;
        } catch (IOException exception) {
            throw new StorageException("Could not list the parts of " + key, exception);
        }
    }

    @Override
    public void completeMultipartUpload(String key, String uploadId, List<UploadedPart> parts) {
        Path dir = uploadDir(key, uploadId);
        List<Path> files = parts.stream().sorted(Comparator.comparingInt(UploadedPart::partNumber))
                .map(part -> dir.resolve(Integer.toString(part.partNumber()))).toList();
        try {
            List<InputStream> streams = new ArrayList<>();
            long length = 0;
            for (Path file : files) {
                streams.add(Files.newInputStream(file));
                length += Files.size(file);
            }
            String contentType = Files.readString(dir.resolve(KEY_FILE + TYPE_SUFFIX));
            put(key, new SequenceInputStream(Collections.enumeration(streams)), length, contentType);
            deleteTree(dir);
        } catch (IOException exception) {
            throw new StorageException("Could not complete the upload of " + key, exception);
        }
    }

    @Override
    public void abortMultipartUpload(String key, String uploadId) {
        try {
            deleteTree(uploadDir(key, uploadId));
        } catch (MultipartUploadNotFoundException missing) {
            // Already gone
        } catch (IOException exception) {
            throw new StorageException("Could not abort the upload of " + key, exception);
        }
    }

    private Path uploadDir(String key, String uploadId) {
        Path dir;
        try {
            dir = root.resolve(MULTIPART_DIR).resolve(UUID.fromString(uploadId).toString());
        } catch (IllegalArgumentException invalid) {
            throw new MultipartUploadNotFoundException("No upload " + uploadId + " for " + key, invalid);
        }
        try {
            if (!Files.isDirectory(dir) || !Files.readString(dir.resolve(KEY_FILE)).equals(key)) {
                throw new MultipartUploadNotFoundException("No upload " + uploadId + " for " + key, null);
            }
        } catch (IOException exception) {
            throw new MultipartUploadNotFoundException("No upload " + uploadId + " for " + key, exception);
        }
        return dir;
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
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
