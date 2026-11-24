package ITubeBackend.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import ITubeBackend.dto.video.*;
import ITubeBackend.entities.VideoType;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.video.VideoService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

@Tag(name = "Videos", description = "Upload, listing, playback and visibility")
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
            @RequestParam(value = "visibility", defaultValue = "PUBLIC") VideoVisibility visibility,
            @RequestParam(value = "requiredTierLevel", required = false) Integer requiredTierLevel,
            @RequestParam(value = "description", required = false) String description) {
        Long channelId = CurrentUser.channelId().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in with a channel before uploading"));
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A video file is required");
        }

        String contentType = file.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.getContentType();
        MediaUpload video = new MediaUpload(file, file.getSize(), contentType);
        MediaUpload thumbnailUpload = thumbnail == null || thumbnail.isEmpty() ? null
                : new MediaUpload(thumbnail, thumbnail.getSize(), thumbnail.getContentType());
        // Streamed from the multipart temp file to object storage; never held in memory
        videoService.saveVideo(new CreateVideo(channelId, video, thumbnailUpload, title, duration, visibility, requiredTierLevel,
                VideoType.VIDEO, false, null, description));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * The home feed (newest first, at most 200). The same for every visitor, so the CDN may cache it briefly:
     * at peak, most home page loads are answered by the CDN edge without reaching ITube at all.
     */
    @GetMapping("/api/videos")
    public ResponseEntity<List<VideoSummaryDto>> listVideos(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(10)).cachePublic().staleWhileRevalidate(Duration.ofSeconds(30)))
                .body(videoService.getVideos(limit));
    }

    /** Videos on the signed-in user's channels, including unlisted and private ones. */
    @GetMapping("/api/videos/mine")
    public List<VideoSummaryDto> listMyVideos() {
        return videoService.getVideosOwnedBy(CurrentUser.require());
    }

    @GetMapping("/api/videos/mine/{videoId}")
    public VideoSummaryDto getMyVideo(@PathVariable Long videoId) {
        return videoService.getOwnedVideo(videoId, CurrentUser.require());
    }

    @PutMapping(path = "/api/videos/{videoId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VideoSummaryDto updateVideo(
            @PathVariable Long videoId,
            @RequestParam("title") String title,
            @RequestParam(value = "visibility", defaultValue = "PUBLIC") VideoVisibility visibility,
            @RequestParam(value = "requiredTierLevel", required = false) Integer requiredTierLevel,
            @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail) {
        Long userId = CurrentUser.require();
        boolean hasThumbnail = thumbnail != null && !thumbnail.isEmpty();
        if (hasThumbnail && (thumbnail.getContentType() == null || !thumbnail.getContentType().startsWith("image/"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The thumbnail must be an image");
        }
        return videoService.updateVideoDetails(videoId, userId, title, visibility, requiredTierLevel,
                hasThumbnail ? new MediaUpload(thumbnail, thumbnail.getSize(), thumbnail.getContentType()) : null);
    }

    @DeleteMapping("/api/videos/{videoId}")
    public ResponseEntity<Void> deleteVideo(@PathVariable Long videoId) {
        videoService.deleteVideo(videoId, CurrentUser.require());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/videos/{videoId}/qualities")
    public List<String> getQualities(@PathVariable Long videoId) {
        return videoService.getQualities(videoId);
    }

    @GetMapping("/api/videos/{videoId}/quality-levels")
    public List<QualityLevelDto> getQualityLevels(@PathVariable Long videoId) {
        return videoService.getQualityLevels(videoId);
    }

    /** Largest bandwidth probe the player may ask for. */
    static final int MAX_PROBE_BYTES = 1024 * 1024;
    /** Random, so gzip on the way can't shrink it and make the connection look faster than it is. */
    private static final byte[] PROBE_BYTES = new byte[MAX_PROBE_BYTES];
    static {
        new java.util.Random(42).nextBytes(PROBE_BYTES);
    }

    /**
     * Bytes for the player to time, to estimate the viewer's connection to ITube and choose a video quality.
     * Never cached anywhere, or the timing would measure the cache instead.
     */
    @GetMapping("/api/videos/bandwidth-probe")
    public ResponseEntity<byte[]> bandwidthProbe(@RequestParam(value = "bytes", defaultValue = "262144") int bytes) {
        int size = Math.max(1024, Math.min(bytes, MAX_PROBE_BYTES));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(java.util.Arrays.copyOf(PROBE_BYTES, size));
    }

    @GetMapping("/api/videos/{videoId}/playback-authorisation")
    public PlaybackAuthorisationDto authorisePlayback(@PathVariable Long videoId) {
        return videoService.authorisePlayback(videoId, CurrentUser.find().orElse(null));
    }

    /** Description, view count and upload time, shown under the player. Never cached: the count keeps changing. */
    @GetMapping("/api/videos/{videoId}/details")
    public ResponseEntity<VideoDetailsDto> details(@PathVariable Long videoId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(videoService.getDetails(videoId, CurrentUser.find().orElse(null)));
    }

    public record ViewResult(boolean counted) {
    }

    /**
     * The player calls this when a video or ad starts playing. Each viewer counts once: signed-in viewers by account,
     * others by the random id their browser sends in X-Viewer-Id. {@code counted} says whether this one was new.
     */
    @PostMapping("/api/videos/{videoId}/views")
    public ResponseEntity<ViewResult> recordView(@PathVariable Long videoId,
                                                 @RequestHeader(value = "X-Viewer-Id", required = false) String viewerId) {
        boolean counted = videoService.recordView(videoId, CurrentUser.find().orElse(null), viewerId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ViewResult(counted));
    }

    /** The ads to play during this video (by default one at the midpoint); empty if it has none. Chosen per view. */
    @GetMapping("/api/videos/{videoId}/ad-breaks")
    public ResponseEntity<List<AdBreakDto>> adBreaks(@PathVariable Long videoId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(videoService.adBreaks(videoId, CurrentUser.find().orElse(null)));
    }

    @PatchMapping("/api/videos/{videoId}/visibility")
    public ResponseEntity<Void> updateVisibility(@PathVariable Long videoId, @Valid @RequestBody UpdateVisibilityRequest request) {
        videoService.updateVisibility(videoId, CurrentUser.require(), request.visibility(), request.requiredTierLevel());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/videos/{videoId}/renditions/{quality}")
    public ResponseEntity<byte[]> getRendition(@PathVariable Long videoId, @PathVariable String quality,
                                               @RequestParam(value = "token", required = false) String token,
                                               @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        boolean openToEveryone = videoService.assertCanStream(videoId, token);
        return videoService.serveMedia(videoService.getRenditionKey(videoId, quality), range, openToEveryone, true);
    }

    /** Adaptive streaming entry point: the qualities the player may switch between, segment by segment. */
    @GetMapping("/api/videos/{videoId}/hls/master.m3u8")
    public ResponseEntity<String> hlsMaster(@PathVariable Long videoId,
                                            @RequestParam(value = "token", required = false) String token) {
        boolean openToEveryone = videoService.assertCanStream(videoId, token);
        return playlist(videoService.hlsMasterPlaylist(videoId, token), openToEveryone);
    }

    @GetMapping("/api/videos/{videoId}/hls/{quality}/index.m3u8")
    public ResponseEntity<String> hlsMediaPlaylist(@PathVariable Long videoId, @PathVariable String quality,
                                                   @RequestParam(value = "token", required = false) String token) {
        boolean openToEveryone = videoService.assertCanStream(videoId, token);
        return playlist(videoService.hlsMediaPlaylist(videoId, quality, token), openToEveryone);
    }

    /** One segment: checked like any other video file, then redirected to the CDN or storage. */
    @GetMapping("/api/videos/{videoId}/hls/{quality}/{file}")
    public ResponseEntity<byte[]> hlsSegment(@PathVariable Long videoId, @PathVariable String quality, @PathVariable String file,
                                             @RequestParam(value = "token", required = false) String token,
                                             @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        boolean openToEveryone = videoService.assertCanStream(videoId, token);
        return videoService.serveMedia(videoService.getHlsSegmentKey(videoId, quality, file), range, openToEveryone, true);
    }

    private static ResponseEntity<String> playlist(String body, boolean openToEveryone) {
        // Paid or private playlists carry the viewer's token, so they must never be shared by a cache
        CacheControl cache = openToEveryone ? CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic()
                : CacheControl.noStore().cachePrivate();
        return ResponseEntity.ok().cacheControl(cache)
                .contentType(MediaType.parseMediaType(ITubeBackend.storage.HlsPlaylist.CONTENT_TYPE)).body(body);
    }

    @GetMapping("/api/videos/{videoId}/file")
    public ResponseEntity<byte[]> getVideoFile(@PathVariable Long videoId,
                                               @RequestParam(value = "token", required = false) String token,
                                               @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        boolean openToEveryone = videoService.assertCanStream(videoId, token);
        return videoService.serveMedia(videoService.getVideoFileKey(videoId), range, openToEveryone, true);
    }

    @GetMapping("/api/videos/{videoId}/thumbnail")
    public ResponseEntity<byte[]> getThumbnail(@PathVariable Long videoId) {
        // Thumbnails are shown on public listings, so they follow the same rule as before: no token needed
        return videoService.serveThumbnail(videoId);
    }
}
