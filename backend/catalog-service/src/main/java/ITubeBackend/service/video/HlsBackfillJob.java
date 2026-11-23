package ITubeBackend.service.video;

import ITubeBackend.entities.VideoEntity;
import ITubeBackend.repository.VideoRepository;
import ITubeBackend.sharding.Shards;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves videos from before HLS onto adaptive streaming. Every few minutes it takes a small batch of READY videos
 * on each shard that have no HLS rendition (single MP4 files per quality, or no renditions at all) and queues them
 * for transcoding again. Small batches keep the transcode workers free for new uploads.
 * <p>
 * The video stays READY and playable from its old files meanwhile. When the HLS result arrives,
 * {@link VideoService#applyTranscodeResult} swaps the renditions over and deletes the old MP4 files. A video whose
 * re-transcode fails stays READY on its old files and is tried again after {@code retry-after}, at most
 * {@code max-attempts} times. Safe on many pods at once: queuing a video twice only transcodes it twice.
 */
@Component
public class HlsBackfillJob {
    private static final Logger log = LoggerFactory.getLogger(HlsBackfillJob.class);

    private final Shards shards;
    private final VideoRepository videoRepository;
    private final VideoTranscodeMessaging messaging;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;
    private final Duration retryAfter;
    private final int maxAttempts;

    public HlsBackfillJob(Shards shards, VideoRepository videoRepository, VideoTranscodeMessaging messaging,
                          TransactionTemplate transactions, Clock clock,
                          @Value("${itube.hls-backfill.enabled:true}") boolean enabled,
                          @Value("${itube.hls-backfill.batch-size:10}") int batchSize,
                          @Value("${itube.hls-backfill.retry-after:PT2H}") Duration retryAfter,
                          @Value("${itube.hls-backfill.max-attempts:3}") int maxAttempts) {
        this.shards = shards;
        this.videoRepository = videoRepository;
        this.messaging = messaging;
        this.transactions = transactions;
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.retryAfter = retryAfter;
        this.maxAttempts = maxAttempts;
    }

    private record Queued(Long videoId, String videoKey) {
    }

    @Scheduled(fixedDelayString = "${itube.hls-backfill.interval:PT5M}", initialDelayString = "PT3M")
    public void run() {
        if (!enabled || batchSize <= 0) return;
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Queued> queued = shards.scatter(shard -> transactions.execute(status -> {
            List<Long> ids = videoRepository.findHlsBackfillCandidates(maxAttempts, now.minus(retryAfter), batchSize);
            List<Queued> due = new ArrayList<>();
            for (VideoEntity video : videoRepository.findAllById(ids)) {
                if (video.getVideoKey() == null) continue;
                video.setHlsBackfillAttempts(video.getHlsBackfillAttempts() + 1);
                video.setHlsBackfillRequestedAt(now);
                due.add(new Queued(video.getVideoId(), video.getVideoKey()));
            }
            return due;
        }));
        // Sent after the batch commits, so a video is never queued without its attempt being counted
        queued.forEach(video -> messaging.send(video.videoId(), video.videoKey()));
        if (!queued.isEmpty()) log.info("Queued {} older videos for HLS transcoding", queued.size());
    }
}
