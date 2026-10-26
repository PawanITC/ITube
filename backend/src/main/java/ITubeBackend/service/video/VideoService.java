package ITubeBackend.service.video;

import ITubeBackend.dto.video.CreateVideo;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.dto.video.VideoAssetDto;
import ITubeBackend.entities.ChannelEntity;
import ITubeBackend.entities.VideoEntity;
import ITubeBackend.repository.ChannelRepository;
import ITubeBackend.repository.VideoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

// SOMETHING NEW HERE, DONT KNOW WHAT

@Service
public class VideoService {
    private final ChannelRepository channelRepository;
    private final VideoRepository videoRepository;
    private final ApplicationEventPublisher eventPublisher;

    public VideoService(ChannelRepository channelRepository, VideoRepository videoRepository, ApplicationEventPublisher eventPublisher) {
        this.channelRepository = channelRepository;
        this.videoRepository = videoRepository;
        this.eventPublisher = eventPublisher;
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

        ChannelEntity channel = channelRepository.findById(createVideo.channelId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));

        VideoEntity video = new VideoEntity();
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
    public VideoAssetDto getSourceForTranscoding(Long videoId) { return getVideoFile(videoId); }

    @Transactional
    public void setProcessingStatus(Long videoId, String status) {
        videoRepository.findById(videoId).ifPresent(video -> video.setProcessingStatus(status));
    }

}
