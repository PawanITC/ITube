package ITubeBackend;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.common.events.ItubeTopics;
import ITubeBackend.dto.video.VideoTranscodedEvent;
import ITubeBackend.service.video.FfmpegTranscoder;
import ITubeBackend.service.video.TranscodeListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranscodeListenerTest {
    private final FfmpegTranscoder transcoder = mock(FfmpegTranscoder.class);
    private final CatalogClient catalog = mock(CatalogClient.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private TranscodeListener listener;

    @BeforeEach
    void setUp() {
        ItubeTopics topics = new ItubeTopics("video.transcode", "video.transcoded", "video.deleted", "account.created",
                "catalog.cache-invalidation", "a", "b", "c", 1, 1);
        listener = new TranscodeListener(transcoder, catalog, kafka, json, topics);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void transcodesFromTheKeyInTheMessageAndReportsTheRenditions() throws Exception {
        when(transcoder.transcode(42L, "videos/a/source"))
                .thenReturn(List.of(new VideoTranscodedEvent.Rendition("360p", "videos/a/renditions/360p.mp4", 10)));

        listener.onTranscode("{\"schemaVersion\":2,\"eventType\":\"VideoUploaded\",\"videoId\":42,\"videoKey\":\"videos/a/source\"}");

        VideoTranscodedEvent sent = sentResult();
        assertThat(sent.status()).isEqualTo("READY");
        assertThat(sent.renditions()).extracting(VideoTranscodedEvent.Rendition::quality).containsExactly("360p");
        verify(catalog, never()).sourceKey(42L);
    }

    @Test
    void oldMessagesWithoutAKeyAskTheCatalog() throws Exception {
        when(catalog.sourceKey(42L)).thenReturn(Optional.of("videos/a/source"));
        when(transcoder.transcode(42L, "videos/a/source")).thenReturn(List.of());

        listener.onTranscode("42");

        assertThat(sentResult().status()).isEqualTo("READY");
    }

    @Test
    void anUnreadableVideoIsReportedFailedInsteadOfRetriedForever() throws Exception {
        when(transcoder.transcode(42L, "videos/a/source"))
                .thenThrow(new FfmpegTranscoder.UnprocessableVideoException("not a video"));

        listener.onTranscode("{\"schemaVersion\":2,\"eventType\":\"VideoUploaded\",\"videoId\":42,\"videoKey\":\"videos/a/source\"}");

        assertThat(sentResult().status()).isEqualTo("FAILED");
    }

    @Test
    void transientFailuresAreThrownSoKafkaRedeliversThem() throws Exception {
        when(transcoder.transcode(42L, "videos/a/source")).thenThrow(new IOException("storage hiccup"));

        assertThatThrownBy(() -> listener.onTranscode(
                "{\"schemaVersion\":2,\"eventType\":\"VideoUploaded\",\"videoId\":42,\"videoKey\":\"videos/a/source\"}"))
                .isInstanceOf(IOException.class);
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void malformedMessagesAreRejectedWithoutRetry() {
        // IllegalArgumentException is "not retryable" in KafkaResilienceConfig: it goes straight to the DLT
        assertThatThrownBy(() -> listener.onTranscode("{\"eventType\":\"Nope\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private VideoTranscodedEvent sentResult() {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("video.transcoded"), eq("42"), payload.capture());
        return json.readValue(payload.getValue(), VideoTranscodedEvent.class);
    }
}
