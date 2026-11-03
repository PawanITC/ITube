package ITubeBackend.controller;

import ITubeBackend.dto.video.CreateVideo;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.dto.video.VideoAssetDto;
import ITubeBackend.dto.video.VideoStreamInfo;
import ITubeBackend.service.video.VideoService;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalTime;
import java.util.List;
import java.util.function.BiFunction;

@RestController
public class VideoController {
    private final VideoService videoService;

    public VideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    @PostMapping(path = "/api/videos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> uploadVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail,
            @RequestParam("title") String title,
            @RequestParam("duration") @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime duration,
            HttpSession session) throws IOException {
        Object sessionChannelId = session.getAttribute("channelId");
        if (!(sessionChannelId instanceof Long channelId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Start a session before uploading");
        }
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A video file is required");
        }

        byte[] thumbnailBytes = thumbnail == null || thumbnail.isEmpty() ? null : thumbnail.getBytes();
        String contentType = file.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.getContentType();
        String thumbnailContentType = thumbnail == null ? null : thumbnail.getContentType();
        videoService.saveVideo(new CreateVideo(channelId, file.getBytes(), contentType, thumbnailBytes, thumbnailContentType, title, duration));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/api/videos")
    public List<VideoSummaryDto> listVideos() {
        return videoService.getVideos();
    }

    @GetMapping("/api/videos/{videoId}/qualities")
    public List<String> getQualities(@PathVariable Long videoId) {
        return videoService.getQualities(videoId);
    }

    @GetMapping("/api/videos/{videoId}/playback-authorisation")
    public PlaybackAuthorisationDto authorisePlayback(@PathVariable Long videoId, HttpSession session) {
        return videoService.authorisePlayback(videoId, CurrentUser.find(session).orElse(null));
    }

    @PatchMapping("/api/videos/{videoId}/visibility")
    public ResponseEntity<Void> updateVisibility(@PathVariable Long videoId, @Valid @RequestBody UpdateVisibilityRequest request,
                                                 HttpSession session) {
        videoService.updateVisibility(videoId, CurrentUser.require(session), request.visibility(), request.requiredTierLevel());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/videos/{videoId}/renditions/{quality}")
    public ResponseEntity<byte[]> getRendition(@PathVariable Long videoId, @PathVariable String quality,
                                               @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        VideoStreamInfo info = videoService.getRenditionStreamInfo(videoId, quality);
        return videoService.streamRange(info, range, (start, length) -> videoService.getRenditionChunk(videoId, quality, start, length));
    }

    @GetMapping("/api/videos/{videoId}/file")
    public ResponseEntity<byte[]> getVideoFile(@PathVariable Long videoId,
                                               @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        VideoStreamInfo info = videoService.getVideoStreamInfo(videoId);
        return videoService.streamRange(info, range, (start, length) -> videoService.getVideoChunk(videoId, start, length));
    }

    @GetMapping("/api/videos/{videoId}/thumbnail")
    public ResponseEntity<byte[]> getThumbnail(@org.springframework.web.bind.annotation.PathVariable Long videoId) {
        VideoAssetDto asset = videoService.getThumbnail(videoId);
        MediaType mediaType = asset.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(asset.contentType());
        return ResponseEntity.ok().contentType(mediaType).body(asset.content());
    }
}
