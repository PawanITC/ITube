package ITubeBackend.repository;

// REVIEW

import ITubeBackend.dto.video.VideoAssetDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class VideoRenditionRepositoryImpl implements VideoRenditionRepository {
    private final JdbcTemplate jdbcTemplate;

    public VideoRenditionRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<String> findRenditionQualities(Long videoId) {
        return jdbcTemplate.queryForList(
                "select quality from video_rendition where video_id = ? order by case quality when '360p' then 1 when '720p' then 2 else 3 end",
                String.class, videoId);
    }

    @Override
    public Optional<VideoAssetDto> findRendition(Long videoId, String quality) {
        return jdbcTemplate.query(
                        "select content, content_type from video_rendition where video_id = ? and quality = ?",
                        (rs, row) -> new VideoAssetDto(rs.getBytes("content"), rs.getString("content_type")),
                        videoId, quality)
                .stream().findFirst();
    }

    @Override
    public void saveRendition(Long videoId, String quality, byte[] bytes) {
        jdbcTemplate.update(
                "insert into video_rendition(video_id, quality, content_type, content) values (?, ?, 'video/mp4', ?) on conflict (video_id, quality) do update set content = excluded.content",
                videoId, quality, bytes);
    }
}
