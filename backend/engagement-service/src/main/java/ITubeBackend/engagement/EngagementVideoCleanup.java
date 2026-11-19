package ITubeBackend.engagement;

import ITubeBackend.dto.video.VideoDeletedEvent;
import ITubeBackend.common.events.Events;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Removes a deleted video's likes and comments. Watch history is kept on purpose: it's the viewer's own
 * record, and History already shows entries for missing videos as removable rows without details.
 * Idempotent: deleting what is already gone is a no-op, so redelivered or replayed messages are harmless.
 */
@Component
public class EngagementVideoCleanup {
    private final LikeStore likeStore;
    private final CommentStore commentStore;
    private final ObjectMapper objectMapper;

    public EngagementVideoCleanup(LikeStore likeStore, CommentStore commentStore, ObjectMapper objectMapper) {
        this.likeStore = likeStore;
        this.commentStore = commentStore;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${itube.topics.video-deleted}", groupId = "itube-engagement-cleanup")
    public void onVideoDeleted(String payload) {
        VideoDeletedEvent event = Events.videoDeleted(objectMapper, payload);
        likeStore.deleteAllForVideo(event.videoId());
        commentStore.deleteAllForVideo(event.videoId());
    }
}
