package ITubeBackend.service.video;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// REVIEW

@Component
public class VideoTranscodeMessaging {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final VideoTranscodeWorker worker;
    private final String topic;

    public VideoTranscodeMessaging(KafkaTemplate<String, String> kafkaTemplate, VideoTranscodeWorker worker,
                                   @Value("${itube.video.transcode-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.worker = worker;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enqueue(VideoUploadedEvent event) {
        kafkaTemplate.send(topic, event.videoId().toString(), event.videoId().toString());
    }

    @KafkaListener(topics = "${itube.video.transcode-topic}")
    public void transcode(String videoId) {
        worker.transcode(Long.parseLong(videoId));
    }
}
