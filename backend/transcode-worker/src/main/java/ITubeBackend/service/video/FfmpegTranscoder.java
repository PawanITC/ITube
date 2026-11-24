package ITubeBackend.service.video;

import ITubeBackend.dto.video.VideoTranscodedEvent.Rendition;
import ITubeBackend.storage.HlsPlaylist;
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
 * Downloads the source from object storage, runs FFmpeg, and uploads each quality as HLS next to it.
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

    /** One output quality: its height and the bitrate caps that keep every segment within the advertised bandwidth. */
    record Ladder(int height, String maxVideoRate, String videoBuffer, String audioRate) {
        String quality() { return height + "p"; }
    }

    static final List<Ladder> LADDER = List.of(
            new Ladder(360, "1000k", "2000k", "96k"),
            new Ladder(720, "3000k", "6000k", "128k"));

    /** Seconds per HLS segment. Keyframes come every 2 s in every quality, so segments line up across qualities. */
    static final int SEGMENT_SECONDS = 6;

    /**
     * Writes each quality as HLS (fragmented MP4 segments plus a playlist) under {@code <folder>/hls/<quality>/}.
     * The player switches quality between segments, so it can adapt to the viewer's connection without a reload.
     * Each returned rendition's key is its playlist; its size is the total of its segments.
     */
    public List<Rendition> transcode(Long videoId, String sourceKey) throws IOException, InterruptedException {
        Path workDir = Files.createTempDirectory("itube-transcode-");
        try {
            String folder = MediaKeys.folderOf(sourceKey);
            Path input = workDir.resolve("source");
            storage.download(sourceKey, input);
            List<Rendition> renditions = new ArrayList<>();
            for (Ladder step : LADDER) {
                String quality = step.quality();
                Path outputDir = Files.createDirectories(workDir.resolve(quality));
                // Runs inside outputDir so the playlist refers to its segments by bare file name
                Process process = new ProcessBuilder(ffmpegArguments(input, step))
                        .directory(outputDir.toFile())
                        .redirectErrorStream(true).redirectOutput(workDir.resolve("ffmpeg-" + quality + ".log").toFile()).start();
                if (!process.waitFor(2, TimeUnit.HOURS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("FFmpeg timed out creating " + quality + " for video " + videoId);
                }
                if (process.exitValue() != 0) {
                    throw new UnprocessableVideoException("FFmpeg could not create " + quality + " for video " + videoId);
                }
                renditions.add(uploadHls(folder, quality, outputDir));
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

    List<String> ffmpegArguments(Path input, Ladder step) {
        return List.of(ffmpegPath, "-y", "-i", input.toString(),
                "-map", "0:v:0", "-map", "0:a:0?",
                "-vf", "scale=-2:" + step.height(),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-profile:v", "main", "-pix_fmt", "yuv420p",
                "-maxrate", step.maxVideoRate(), "-bufsize", step.videoBuffer(),
                "-force_key_frames", "expr:gte(t,n_forced*2)", "-sc_threshold", "0",
                "-c:a", "aac", "-b:a", step.audioRate(), "-ac", "2",
                "-f", "hls", "-hls_time", String.valueOf(SEGMENT_SECONDS), "-hls_playlist_type", "vod",
                "-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4",
                "-hls_segment_filename", "seg_%05d.m4s", HlsPlaylist.FILE_NAME);
    }

    /** Uploads the segments first and the playlist last, so a playlist in storage always has all its segments. */
    private Rendition uploadHls(String folder, String quality, Path outputDir) throws IOException {
        Path playlist = outputDir.resolve(HlsPlaylist.FILE_NAME);
        if (!Files.isRegularFile(playlist)) {
            throw new UnprocessableVideoException("FFmpeg wrote no playlist for " + quality);
        }
        String prefix = MediaKeys.hlsFolder(folder, quality);
        long totalBytes = 0;
        for (String file : HlsPlaylist.referencedFiles(Files.readString(playlist))) {
            Path segment = outputDir.resolve(file).normalize();
            if (!HlsPlaylist.isMediaFileName(file) || !segment.startsWith(outputDir) || !Files.isRegularFile(segment)) {
                throw new UnprocessableVideoException("Unexpected HLS output " + file + " for " + quality);
            }
            long size = Files.size(segment);
            try (InputStream in = Files.newInputStream(segment)) {
                storage.put(prefix + file, in, size, file.endsWith(".mp4") ? "video/mp4" : HlsPlaylist.SEGMENT_CONTENT_TYPE);
            }
            totalBytes += size;
        }
        String playlistKey = MediaKeys.hlsPlaylist(folder, quality);
        storage.put(playlistKey, Files.readAllBytes(playlist), HlsPlaylist.CONTENT_TYPE);
        return new Rendition(quality, playlistKey, totalBytes);
    }
}
