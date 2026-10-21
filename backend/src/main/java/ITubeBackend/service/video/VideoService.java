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
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
public class VideoService {
    private final ChannelRepository channelRepository;
    private final VideoRepository videoRepository;

    public VideoService(ChannelRepository channelRepository, VideoRepository videoRepository) {
        this.channelRepository = channelRepository;
        this.videoRepository = videoRepository;
    }

    @Transactional
    public void saveVideo(CreateVideo createVideo) {
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
        videoRepository.save(video);
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

}
