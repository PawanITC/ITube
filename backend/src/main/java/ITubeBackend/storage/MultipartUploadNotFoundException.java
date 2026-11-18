package ITubeBackend.storage;

/** The multipart upload was completed, aborted or expired (e.g. by the bucket's lifecycle rule). */
public class MultipartUploadNotFoundException extends StorageException {
    public MultipartUploadNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
