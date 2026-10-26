package ITubeBackend.repository;

// REVIEW

import ITubeBackend.dto.video.VideoAssetDto;

import java.util.List;
import java.util.Optional;

public interface VideoRenditionRepository {
    List<String> findRenditionQualities(Long videoId);

    Optional<VideoAssetDto> findRendition(Long videoId, String quality);

    void saveRendition(Long videoId, String quality, byte[] bytes);
}
