package ITubeBackend.service.video;

import ITubeBackend.dto.video.VideoAssetDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// REVIEW

@Service
public class VideoTranscodeWorker {
    private final VideoService videoService;
    private final String ffmpegPath;

    public VideoTranscodeWorker(VideoService videoService, @Value("${itube.video.ffmpeg-path}") String ffmpegPath) {
        this.videoService = videoService;
        this.ffmpegPath = ffmpegPath;
    }

    public void transcode(Long videoId) {
        Path workDir = null;
        try {
            videoService.setProcessingStatus(videoId, "PROCESSING");
            VideoAssetDto source = videoService.getSourceForTranscoding(videoId);
            workDir = Files.createTempDirectory("itube-transcode-");
            Path input = workDir.resolve("source");
            Files.write(input, source.content());
            for (int height : new int[]{360, 720}) {
                Path output = workDir.resolve(height + "p.mp4");
                Process process = new ProcessBuilder(ffmpegPath, "-y", "-i", input.toString(), "-vf", "scale=-2:" + height,
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-c:a", "aac", "-movflags", "+faststart", output.toString())
                        .redirectErrorStream(true).redirectOutput(workDir.resolve("ffmpeg.log").toFile()).start();
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.HOURS) || process.exitValue() != 0) {
                    process.destroyForcibly();
                    throw new IllegalStateException("FFmpeg failed to create " + height + "p rendition for video " + videoId);
                }
                videoService.saveRendition(videoId, height + "p", Files.readAllBytes(output));
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
