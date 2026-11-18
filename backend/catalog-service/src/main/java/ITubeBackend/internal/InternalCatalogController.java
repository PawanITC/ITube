package ITubeBackend.internal;

import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.dto.channel.EnsureChannelRequest;
import ITubeBackend.dto.video.InternalVideoSummary;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.service.channel.ChannelService;
import ITubeBackend.service.video.VideoService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Service-to-service API of the catalog (never routed by the gateway; guarded by the internal token).
 * Callers: engagement-service (video summaries/access), payments-service (channels), identity-service
 * (create/list a user's channels) and transcode-worker (source key for old messages).
 */
@Hidden
@RestController
@RequestMapping("/internal")
public class InternalCatalogController {
    private static final int MAX_BATCH = 500;

    private final VideoService videoService;
    private final ChannelService channelService;

    public InternalCatalogController(VideoService videoService, ChannelService channelService) {
        this.videoService = videoService;
        this.channelService = channelService;
    }

    @PostMapping("/videos/summaries")
    public List<InternalVideoSummary> summaries(@RequestBody List<Long> videoIds) {
        if (videoIds.size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most " + MAX_BATCH + " ids per call");
        }
        return videoService.internalSummaries(videoIds);
    }

    @GetMapping("/videos/{videoId}/access")
    public ResponseEntity<VideoAccessDto> access(@PathVariable Long videoId) {
        return ResponseEntity.of(videoService.findAccess(videoId));
    }

    @GetMapping("/videos/{videoId}/source")
    public ResponseEntity<Map<String, String>> source(@PathVariable Long videoId) {
        return ResponseEntity.of(videoService.findSourceKey(videoId).map(key -> Map.of("videoKey", key)));
    }

    @GetMapping("/channels/{channelId}")
    public ResponseEntity<ChannelDto> channel(@PathVariable Long channelId) {
        return ResponseEntity.of(channelService.findChannel(channelId));
    }

    @GetMapping("/channels")
    public List<ChannelDto> channelsOwnedBy(@RequestParam Long ownerUserId) {
        return channelService.channelsOwnedBy(ownerUserId);
    }

    @PostMapping("/channels")
    public ChannelDto ensureChannel(@RequestBody EnsureChannelRequest request) {
        if (request.userId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        return channelService.ensureChannel(request.userId(), request.channelName());
    }
}
