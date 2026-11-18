package ITubeBackend.service.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Aborts resumable uploads nobody finished in time, so their parts stop using storage. On AWS the bucket's
 * lifecycle rule would also abort them; SeaweedFS has no such rule, and either way the rows need removing.
 * Safe on several instances at once: aborting or deleting something already gone is not an error.
 */
@Component
public class UploadExpiryJob {
    private static final Logger log = LoggerFactory.getLogger(UploadExpiryJob.class);
    private static final int BATCH_SIZE = 100;
    private final ResumableUploadService uploads;

    public UploadExpiryJob(ResumableUploadService uploads) {
        this.uploads = uploads;
    }

    @Scheduled(fixedDelayString = "${itube.upload.expiry-interval:PT1H}", initialDelayString = "PT5M")
    public void removeExpiredUploads() {
        int removed;
        do {
            removed = uploads.removeExpired(BATCH_SIZE);
            if (removed > 0) log.info("Removed {} expired uploads", removed);
        } while (removed == BATCH_SIZE);
    }
}
