package ITubeBackend.service.video;

import ITubeBackend.dto.video.VideoTranscodedEvent.Rendition;
import ITubeBackend.storage.MediaKeys;
import ITubeBackend.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Downloads the source from object storage, runs FFmpeg, and uploads each rendition next to it.
 * Only ids and keys travel over Kafka; the bytes go storage -> worker disk -> storage. Workers are
 * stateless and need no database, so throughput scales with transcode topic partitions and worker replicas.
 * Re-running for the same video overwrites the same keys, so a redelivered message is harmless.
 */
@Service
public class FfmpegTranscoder {
    /** The source itself is unusable (corrupt, not a video): retrying won't help. */
    public static class UnprocessableVideoException extends RuntimeException {
        public UnprocessableVideoException(String message) {
            super(message);
        }
    }

    private final ObjectStorage storage;
    private final String ffmpegPath;

    public FfmpegTranscoder(ObjectStorage storage, @Value("${itube.video.ffmpeg-path}") String ffmpegPath) {
        this.storage = storage;
        this.ffmpegPath = ffmpegPath;
    }

    public List<Rendition> transcode(Long videoId, String sourceKey) throws IOException, InterruptedException {
        Path workDir = Files.createTempDirectory("itube-transcode-");
        try {
            String folder = MediaKeys.folderOf(sourceKey);
            Path input = workDir.resolve("source");
            storage.download(sourceKey, input);
            List<Rendition> renditions = new ArrayList<>();
            for (int height : new int[]{360, 720}) {
                String quality = height + "p";
                Path output = workDir.resolve(quality + ".mp4");
                Process process = new ProcessBuilder(ffmpegPath, "-y", "-i", input.toString(), "-vf", "scale=-2:" + height,
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-c:a", "aac", "-movflags", "+faststart", output.toString())
                        .redirectErrorStream(true).redirectOutput(workDir.resolve("ffmpeg.log").toFile()).start();
                if (!process.waitFor(2, TimeUnit.HOURS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("FFmpeg timed out creating " + quality + " for video " + videoId);
                }
                if (process.exitValue() != 0) {
                    throw new UnprocessableVideoException("FFmpeg could not create " + quality + " for video " + videoId);
                }
                String key = MediaKeys.rendition(folder, quality);
                long size = Files.size(output);
                try (InputStream in = Files.newInputStream(output)) {
                    storage.put(key, in, size, "video/mp4");
                }
                renditions.add(new Rendition(quality, key, size));
            }
            return renditions;
        } finally {
            try (var paths = Files.walk(workDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // temp files; the OS cleans up eventually
                    }
                });
            } catch (IOException ignored) {
                // as above
            }
        }
    }
}
