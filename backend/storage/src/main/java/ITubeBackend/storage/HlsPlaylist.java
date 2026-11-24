package ITubeBackend.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HLS media playlists (one per quality, written by the transcode worker as {@code .../hls/<quality>/index.m3u8}).
 * The playlist names its init segment and media segments by file name, relative to its own folder. Those files are
 * the only other objects in that folder, so the playlist doubles as the list of keys to tag or delete.
 */
public final class HlsPlaylist {
    public static final String CONTENT_TYPE = "application/vnd.apple.mpegurl";
    public static final String SEGMENT_CONTENT_TYPE = "video/iso.segment";
    public static final String FILE_NAME = "index.m3u8";

    /** Init segment and media segment names the worker writes; anything else is refused when serving. */
    private static final Pattern MEDIA_FILE = Pattern.compile("[A-Za-z0-9_-]{1,64}\\.(m4s|mp4)");
    private static final Pattern MAP_URI = Pattern.compile("(#EXT-X-MAP:.*URI=\")([^\"]+)(\".*)");

    private HlsPlaylist() {
    }

    public static boolean isPlaylistKey(String key) {
        return key != null && key.endsWith(".m3u8");
    }

    /** "videos/{uuid}/hls/720p/index.m3u8" -> "videos/{uuid}/hls/720p/" */
    public static String folderOf(String playlistKey) {
        return playlistKey.substring(0, playlistKey.lastIndexOf('/') + 1);
    }

    /** Whether {@code name} looks like one of the worker's segment files (no paths, no other extensions). */
    public static boolean isMediaFileName(String name) {
        return name != null && MEDIA_FILE.matcher(name).matches();
    }

    /** Init and media segment names the playlist refers to, in order. */
    public static List<String> referencedFiles(String playlist) {
        List<String> files = new ArrayList<>();
        rewrite(playlist, uri -> {
            files.add(uri);
            return uri;
        });
        return files;
    }

    /** The playlist with every file reference (segment lines and the EXT-X-MAP init URI) passed through {@code mapper}. */
    public static String rewrite(String playlist, UnaryOperator<String> mapper) {
        StringBuilder out = new StringBuilder(playlist.length() + 256);
        for (String line : playlist.split("\r?\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                out.append(line);
            } else if (trimmed.startsWith("#")) {
                Matcher map = MAP_URI.matcher(trimmed);
                out.append(map.matches() ? map.group(1) + mapper.apply(map.group(2)) + map.group(3) : line);
            } else {
                out.append(mapper.apply(trimmed));
            }
            out.append('\n');
        }
        // split(-1) keeps a trailing empty line; don't add a second newline for it
        if (playlist.endsWith("\n")) out.setLength(out.length() - 1);
        return out.toString();
    }

    public static String read(ObjectStorage storage, String key) {
        ObjectStorage.ObjectInfo info = storage.stat(key)
                .orElseThrow(() -> new StorageException("Playlist " + key + " not found", null));
        return new String(storage.readRange(key, 0, Math.toIntExact(info.length())), StandardCharsets.UTF_8);
    }

    /**
     * Every object of one quality: its segments and the playlist itself (last, so a half-finished delete can be
     * retried from the playlist). Just the playlist key if it can't be read.
     */
    public static List<String> objectKeys(ObjectStorage storage, String playlistKey) {
        List<String> keys = new ArrayList<>();
        if (storage.stat(playlistKey).isPresent()) {
            String folder = folderOf(playlistKey);
            for (String file : referencedFiles(read(storage, playlistKey))) {
                if (isMediaFileName(file)) keys.add(folder + file);
            }
        }
        keys.add(playlistKey);
        return keys;
    }
}
