package ITubeBackend.storage;

import java.util.UUID;

/**
 * Object key layout. Every video gets a random folder, so keys are known before the database row
 * exists (the upload happens outside the transaction) and can't be guessed from the video id.
 */
public final class MediaKeys {
    private MediaKeys() {
    }

    public static String newVideoFolder() {
        return "videos/" + UUID.randomUUID() + "/";
    }

    public static String source(String folder) {
        return folder + "source";
    }

    /** Thumbnails get a fresh key on every change so a CDN never serves the old image. */
    public static String thumbnail(String folder) {
        return folder + "thumbnail-" + UUID.randomUUID();
    }

    public static String rendition(String folder, String quality) {
        return folder + "renditions/" + quality + ".mp4";
    }

    /** "videos/{uuid}/source" -> "videos/{uuid}/" */
    public static String folderOf(String key) {
        int slash = key.indexOf('/', "videos/".length());
        return slash < 0 ? key + "/" : key.substring(0, slash + 1);
    }
}
