package ITubeBackend.service.video;

import ITubeBackend.dto.video.CreateVideo;
import ITubeBackend.dto.video.PlaybackAuthorisationDto;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.dto.video.VideoAssetDto;
import ITubeBackend.dto.video.VideoUploadedEvent;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.entities.VideoEntity;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.service.entitlement.EntitlementService;
import ITubeBackend.service.entitlement.PlaybackTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
public class VideoService {
    private final ChannelRepository channelRepository;
    private final VideoRepository videoRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final EntitlementService entitlementService;
    private final PlaybackTokenService playbackTokenService;

    public VideoService(ChannelRepository channelRepository, VideoRepository videoRepository, ApplicationEventPublisher eventPublisher,
                        EntitlementService entitlementService, PlaybackTokenService playbackTokenService) {
        this.channelRepository = channelRepository;
        this.videoRepository = videoRepository;
        this.eventPublisher = eventPublisher;
        this.entitlementService = entitlementService;
        this.playbackTokenService = playbackTokenService;
    }

    @Transactional
    public Long saveVideo(CreateVideo createVideo) {
        if (createVideo.title() == null || createVideo.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video title is required");
        }
        if (createVideo.video() == null || createVideo.video().length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A video file is required");
        }
        if (createVideo.duration() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Video duration is required");
        }

        VideoVisibility visibility = createVideo.visibility() == null ? VideoVisibility.PUBLIC : createVideo.visibility();
        validateTierLevel(visibility, createVideo.requiredTierLevel());

        ChannelEntity channel = channelRepository.findById(createVideo.channelId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));

        VideoEntity video = new VideoEntity();
        video.setVisibility(visibility);
        video.setRequiredTierLevel(visibility == VideoVisibility.MEMBERS_ONLY ? createVideo.requiredTierLevel() : null);
        video.setChannel(channel);
        video.setVideo(createVideo.video());
        video.setVideoContentType(createVideo.videoContentType());
        video.setThumbnail(createVideo.thumbnail());
        video.setThumbnailContentType(createVideo.thumbnailContentType());
        video.setTitle(createVideo.title().trim());
        video.setDuration(createVideo.duration());
        video.setProcessingStatus("PROCESSING");
        Long videoId = videoRepository.save(video).getVideoId();
        eventPublisher.publishEvent(new VideoUploadedEvent(videoId));
        return videoId;
    }

    /** Checks entitlement on the server and, if allowed, issues a short-lived playback token. */
    @Transactional(readOnly = true)
    public PlaybackAuthorisationDto authorisePlayback(Long videoId, Long userId) {
        VideoAccessDto access = requireAccess(videoId);
        EntitlementService.PlaybackDecision decision = entitlementService.decide(userId, access);
        if (access.visibility() == VideoVisibility.PRIVATE && !decision.allowed()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
        String sourceUrl = "/api/videos/" + videoId + "/file";
        String visibility = access.visibility().name();
        if (!decision.allowed()) {
            return new PlaybackAuthorisationDto(videoId, access.channelId(), visibility, access.requiredTierLevel(),
                    false, decision.reason(), null, null, null);
        }
        if (access.visibility().openToEveryone()) {
            return new PlaybackAuthorisationDto(videoId, access.channelId(), visibility, access.requiredTierLevel(),
                    true, null, sourceUrl, null, null);
        }
        PlaybackTokenService.IssuedToken token = playbackTokenService.issue(videoId, userId);
        return new PlaybackAuthorisationDto(videoId, access.channelId(), visibility, access.requiredTierLevel(),
                true, null, sourceUrl + "?token=" + token.token(), token.token(), token.expiresAt());
    }

    /**
     * Guards the media endpoints. Videos that aren't open to everyone require a valid playback
     * token, so a members-only file can't be fetched by guessing its URL.
     * Returns true if the video is open to everyone (and so may be cached publicly).
     */
    @Transactional(readOnly = true)
    public boolean assertCanStream(Long videoId, String token) {
        VideoAccessDto access = requireAccess(videoId);
        if (access.visibility().openToEveryone()) return true;
        if (!playbackTokenService.isValid(videoId, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Playback authorisation required");
        }
        return false;
    }

    @Transactional
    public void updateVisibility(Long videoId, Long userId, VideoVisibility visibility, Integer requiredTierLevel) {
        VideoAccessDto access = requireAccess(videoId);
        if (!access.ownerUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the channel owner can change visibility");
        }
        validateTierLevel(visibility, requiredTierLevel);
        VideoEntity video = videoRepository.findById(videoId).orElseThrow();
        video.setVisibility(visibility);
        video.setRequiredTierLevel(visibility == VideoVisibility.MEMBERS_ONLY ? requiredTierLevel : null);
    }

    private VideoAccessDto requireAccess(Long videoId) {
        return videoRepository.findAccess(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
    }

    private static void validateTierLevel(VideoVisibility visibility, Integer requiredTierLevel) {
        if (requiredTierLevel == null) return;
        if (visibility != VideoVisibility.MEMBERS_ONLY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A tier level only applies to members-only videos");
        }
        if (requiredTierLevel < 1 || requiredTierLevel > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tier level must be between 1 and 10");
        }
    }

    @Transactional(readOnly = true)
    public List<VideoSummaryDto> getVideos() {
        return videoRepository.findVideoSummaries();
    }

    @Transactional(readOnly = true)
    public VideoAssetDto getVideoFile(Long videoId) {
        return videoRepository.findVideoAsset(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
    }

    @Transactional(readOnly = true)
    public VideoAssetDto getThumbnail(Long videoId) {
        return videoRepository.findThumbnailAsset(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Thumbnail not found"));
    }

    @Transactional(readOnly = true)
    public List<String> getQualities(Long videoId) {
        if (!videoRepository.existsById(videoId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        return videoRepository.findRenditionQualities(videoId);
    }

    @Transactional(readOnly = true)
    public VideoAssetDto getRendition(Long videoId, String quality) {
        return videoRepository.findRendition(videoId, quality)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video quality not found"));
    }

    @Transactional
    public void saveRendition(Long videoId, String quality, byte[] bytes) {
        videoRepository.saveRendition(videoId, quality, bytes);
    }

    @Transactional
    public VideoAssetDto getSourceForTranscoding(Long videoId) {
        return getVideoFile(videoId);
    }

    @Transactional
    public void setProcessingStatus(Long videoId, String status) {
        videoRepository.findById(videoId).ifPresent(video -> video.setProcessingStatus(status));
    }

}
