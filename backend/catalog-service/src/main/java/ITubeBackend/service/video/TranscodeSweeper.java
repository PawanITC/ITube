package ITubeBackend.service.video;

import ITubeBackend.entities.VideoEntity;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.sharding.Shards;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Self-healing for the transcode pipeline. A video can get stuck in PROCESSING if its message never reached Kafka
 * (Kafka down at that moment) or every worker crashed on it. Every few minutes this looks on each shard for videos
 * whose transcode was requested more than {@code stuck-after} ago, and queues them again - up to
 * {@code max-attempts} times, then marks them FAILED so the creator sees it instead of waiting forever.
 * <p>
 * Safe on many pods at once: re-queuing a video twice only transcodes it twice (results are idempotent).
 */
@Component
public class TranscodeSweeper {
    private static final Logger log = LoggerFactory.getLogger(TranscodeSweeper.class);
    private static final int BATCH = 50;

    private final Shards shards;
    private final VideoRepository videoRepository;
    private final VideoTranscodeMessaging messaging;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration stuckAfter;
    private final int maxAttempts;

    public TranscodeSweeper(Shards shards, VideoRepository videoRepository, VideoTranscodeMessaging messaging,
                            TransactionTemplate transactions, Clock clock,
                            @Value("${itube.transcode.stuck-after:PT30M}") Duration stuckAfter,
                            @Value("${itube.transcode.max-attempts:5}") int maxAttempts) {
        this.shards = shards;
        this.videoRepository = videoRepository;
        this.messaging = messaging;
        this.transactions = transactions;
        this.clock = clock;
        this.stuckAfter = stuckAfter;
        this.maxAttempts = maxAttempts;
    }

    private record Requeue(Long videoId, String videoKey) {
    }

    @Scheduled(fixedDelayString = "${itube.transcode.sweep-interval:PT5M}", initialDelayString = "PT2M")
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Requeue> requeue = shards.scatter(shard -> transactions.execute(status -> {
            List<Requeue> due = new ArrayList<>();
            for (VideoEntity video : videoRepository.findStuckProcessing(now.minus(stuckAfter), Limit.of(BATCH))) {
                if (video.getTranscodeAttempts() >= maxAttempts || video.getVideoKey() == null) {
                    video.setProcessingStatus("FAILED");
                    log.warn("Video {} still not transcoded after {} attempts; marked FAILED", video.getVideoId(),
                            video.getTranscodeAttempts());
                    continue;
                }
                video.setTranscodeAttempts(video.getTranscodeAttempts() + 1);
                video.setTranscodeRequestedAt(now);
                due.add(new Requeue(video.getVideoId(), video.getVideoKey()));
            }
            return due;
        }));
        requeue.forEach(video -> messaging.send(video.videoId(), video.videoKey()));
        if (!requeue.isEmpty()) log.info("Re-queued {} videos stuck in PROCESSING", requeue.size());
    }
}
