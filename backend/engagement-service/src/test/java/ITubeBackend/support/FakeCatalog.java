package ITubeBackend.support;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.common.client.DownstreamUnavailableException;
import ITubeBackend.dto.channel.ChannelDto;
import ITubeBackend.dto.video.InternalVideoSummary;
import ITubeBackend.dto.video.VideoAccessDto;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.entities.VideoVisibility;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.LocalTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stands in for catalog-service in engagement tests: videos live in memory and can be changed (made private,
 * retitled, deleted) between calls, and the whole "service" can be taken down to test fallbacks.
 * Import with {@code @Import(FakeCatalog.Config.class)} and autowire {@link FakeCatalog}.
 */
public class FakeCatalog implements CatalogClient {
    private final Map<Long, Video> videos = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1000);
    private volatile boolean down;

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        FakeCatalog fakeCatalog() {
            return new FakeCatalog();
        }
    }

    /** A video as the catalog would hold it; mutable so tests can change it like an entity. */
    public static final class Video {
        private final Long videoId;
        private final Long ownerUserId;
        private final Long channelId;
        private String title;
        private String channelName = "Test channel";
        private VideoVisibility visibility;
        private Integer requiredTierLevel;
        private boolean thumbnail;

        Video(Long videoId, Long ownerUserId, String title, VideoVisibility visibility) {
            this.videoId = videoId;
            this.ownerUserId = ownerUserId;
            this.channelId = 500 + ownerUserId;
            this.title = title;
            this.visibility = visibility;
        }

        public Long getVideoId() { return videoId; }
        public Long ownerUserId() { return ownerUserId; }
        public Video setTitle(String title) { this.title = title; return this; }
        public Video setChannelName(String channelName) { this.channelName = channelName; return this; }
        public Video setVisibility(VideoVisibility visibility) { this.visibility = visibility; return this; }
        public Video setRequiredTierLevel(Integer level) { this.requiredTierLevel = level; return this; }
        public Video setThumbnail(boolean thumbnail) { this.thumbnail = thumbnail; return this; }

        VideoSummaryDto summary() {
            return new VideoSummaryDto(videoId, channelId, title, channelName, LocalTime.of(0, 1), thumbnail,
                    "READY", visibility, requiredTierLevel);
        }

        VideoAccessDto access() {
            return new VideoAccessDto(videoId, channelId, ownerUserId, visibility, requiredTierLevel, LocalTime.of(0, 1));
        }
    }

    public Video add(Long ownerUserId, String title, VideoVisibility visibility) {
        Video video = new Video(ids.incrementAndGet(), ownerUserId, title, visibility);
        if (visibility == VideoVisibility.MEMBERS_ONLY) video.setRequiredTierLevel(1);
        videos.put(video.getVideoId(), video);
        return video;
    }

    public void delete(Long videoId) {
        videos.remove(videoId);
    }

    public void reset() {
        videos.clear();
        down = false;
    }

    /** Simulates catalog-service being unreachable (circuit breaker open, timeouts...). */
    public void setDown(boolean down) {
        this.down = down;
    }

    @Override
    public Map<Long, VideoSummaryDto> visibleVideoSummaries(Collection<Long> videoIds, Long viewerUserId) {
        failIfDown();
        Map<Long, VideoSummaryDto> visible = new LinkedHashMap<>();
        for (Long id : videoIds) {
            Video video = videos.get(id);
            if (video == null) continue;
            InternalVideoSummary internal = new InternalVideoSummary(video.summary(), video.ownerUserId());
            if (internal.visibleTo(viewerUserId)) visible.put(id, internal.summary());
        }
        return visible;
    }

    @Override
    public Optional<VideoAccessDto> videoAccess(Long videoId) {
        failIfDown();
        return Optional.ofNullable(videos.get(videoId)).map(Video::access);
    }

    @Override
    public Optional<ChannelDto> channel(Long channelId) {
        failIfDown();
        return Optional.empty();
    }

    @Override
    public List<ChannelDto> channelsOwnedBy(Long userId) {
        failIfDown();
        return List.of();
    }

    @Override
    public ChannelDto ensureChannel(Long userId, String channelName) {
        failIfDown();
        return new ChannelDto(500 + userId, userId, channelName);
    }

    @Override
    public Optional<String> sourceKey(Long videoId) {
        failIfDown();
        return Optional.empty();
    }

    private void failIfDown() {
        if (down) throw new DownstreamUnavailableException("catalog", new java.net.ConnectException("simulated outage"));
    }
}
