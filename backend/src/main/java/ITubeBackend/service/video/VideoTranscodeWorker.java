package ITubeBackend.service.video;

import ITubeBackend.dto.video.VideoMediaDto;
import ITubeBackend.storage.MediaKeys;
import ITubeBackend.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Downloads the source from object storage, runs FFmpeg, and uploads each rendition next to it.
 * Only ids and keys travel over Kafka; the bytes go storage -> worker disk -> storage. Workers are
 * stateless, so throughput scales with transcode topic partitions and worker replicas.
 */
@Service
public class VideoTranscodeWorker {
    private final VideoService videoService;
    private final ObjectStorage storage;
    private final String ffmpegPath;

    public VideoTranscodeWorker(VideoService videoService, ObjectStorage storage,
                                @Value("${itube.video.ffmpeg-path}") String ffmpegPath) {
        this.videoService = videoService;
        this.storage = storage;
        this.ffmpegPath = ffmpegPath;
    }

    public void transcode(Long videoId) {
        Path workDir = null;
        try {
            VideoMediaDto source = videoService.getSourceForTranscoding(videoId);
            String folder = MediaKeys.folderOf(source.videoKey());
            workDir = Files.createTempDirectory("itube-transcode-");
            Path input = workDir.resolve("source");
            storage.download(source.videoKey(), input);
            for (int height : new int[]{360, 720}) {
                String quality = height + "p";
                Path output = workDir.resolve(quality + ".mp4");
                Process process = new ProcessBuilder(ffmpegPath, "-y", "-i", input.toString(), "-vf", "scale=-2:" + height,
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-c:a", "aac", "-movflags", "+faststart", output.toString())
                        .redirectErrorStream(true).redirectOutput(workDir.resolve("ffmpeg.log").toFile()).start();
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.HOURS) || process.exitValue() != 0) {
                    process.destroyForcibly();
                    throw new IllegalStateException("FFmpeg failed to create " + quality + " rendition for video " + videoId);
                }
                String key = MediaKeys.rendition(folder, quality);
                long size = Files.size(output);
                try (InputStream in = Files.newInputStream(output)) {
                    storage.put(key, in, size, "video/mp4");
                }
                videoService.saveRendition(videoId, quality, key, size);
            }
            videoService.setProcessingStatus(videoId, "READY");
        } catch (Exception exception) {
            videoService.setProcessingStatus(videoId, "FAILED");
            throw new IllegalStateException("Video transcoding failed for video " + videoId, exception);
        } finally {
            if (workDir != null) {
                try (var paths = Files.walk(workDir)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
                } catch (IOException ignored) { }
            }
        }
    }
}
