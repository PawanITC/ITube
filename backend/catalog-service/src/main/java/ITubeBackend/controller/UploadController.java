package ITubeBackend.controller;

import ITubeBackend.dto.video.MediaUpload;
import ITubeBackend.dto.video.ResumableUploadDtos.CompletedUploadDto;
import ITubeBackend.dto.video.ResumableUploadDtos.PartUrlDto;
import ITubeBackend.dto.video.ResumableUploadDtos.PartUrlsRequest;
import ITubeBackend.dto.video.ResumableUploadDtos.StartUploadRequest;
import ITubeBackend.dto.video.ResumableUploadDtos.UploadSessionDto;
import ITubeBackend.security.CurrentUser;
import ITubeBackend.service.video.ResumableUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Resumable video uploads. Flow: POST /api/uploads (start, or resume the same file) -> POST .../part-urls ->
 * PUT each missing part to its URL -> POST .../complete. GET /api/uploads lists unfinished uploads to resume.
 */
@Tag(name = "Uploads", description = "Resumable video uploads, sent as parts straight to object storage")
@RestController
@RequestMapping("/api/uploads")
public class UploadController {
    private final ResumableUploadService uploads;

    public UploadController(ResumableUploadService uploads) {
        this.uploads = uploads;
    }

    @Operation(summary = "Start an upload, or resume the open upload of the same file")
    @PostMapping
    public UploadSessionDto start(@Valid @RequestBody StartUploadRequest request) {
        Long userId = CurrentUser.require();
        // 401 without a channel: the frontend refreshes the token, which picks up a channel created since sign-in
        Long channelId = CurrentUser.channelId().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in with a channel before uploading"));
        return uploads.start(userId, channelId, request);
    }

    @Operation(summary = "The signed-in user's unfinished uploads")
    @GetMapping
    public List<UploadSessionDto> listOpen() {
        return uploads.listOpen(CurrentUser.require());
    }

    @Operation(summary = "One unfinished upload, with the parts already stored")
    @GetMapping("/{uploadId}")
    public UploadSessionDto get(@PathVariable UUID uploadId) {
        return uploads.get(uploadId, CurrentUser.require());
    }

    @Operation(summary = "URLs to PUT parts to (pre-signed object storage URLs where the store supports them)")
    @PostMapping("/{uploadId}/part-urls")
    public List<PartUrlDto> partUrls(@PathVariable UUID uploadId, @Valid @RequestBody PartUrlsRequest request) {
        return uploads.partUrls(uploadId, CurrentUser.require(), request.partNumbers());
    }

    @Operation(summary = "Upload one part through the backend (only for stores that can't pre-sign URLs)")
    @PutMapping(path = "/{uploadId}/parts/{partNumber}", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Void> uploadPart(@PathVariable UUID uploadId, @PathVariable int partNumber,
                                           HttpServletRequest request) throws IOException {
        String etag = uploads.uploadPart(uploadId, CurrentUser.require(), partNumber,
                request.getInputStream(), request.getContentLengthLong());
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag).build();
    }

    @Operation(summary = "Join the parts and post the video; the thumbnail is optional")
    @PostMapping(path = "/{uploadId}/complete", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.ALL_VALUE})
    public ResponseEntity<CompletedUploadDto> complete(@PathVariable UUID uploadId,
                                                       @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail) {
        Long userId = CurrentUser.require();
        MediaUpload thumbnailUpload = null;
        if (thumbnail != null && !thumbnail.isEmpty()) {
            if (thumbnail.getContentType() == null || !thumbnail.getContentType().startsWith("image/")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The thumbnail must be an image");
            }
            thumbnailUpload = new MediaUpload(thumbnail, thumbnail.getSize(), thumbnail.getContentType());
        }
        Long videoId = uploads.complete(uploadId, userId, thumbnailUpload);
        return ResponseEntity.status(HttpStatus.CREATED).body(new CompletedUploadDto(videoId));
    }

    @Operation(summary = "Discard an unfinished upload")
    @DeleteMapping("/{uploadId}")
    public ResponseEntity<Void> discard(@PathVariable UUID uploadId) {
        uploads.discard(uploadId, CurrentUser.require());
        return ResponseEntity.noContent().build();
    }
}
