package ITubeBackend.common.events;

import ITubeBackend.dto.accounts.AccountCreatedEvent;
import ITubeBackend.dto.video.VideoDeletedEvent;
import ITubeBackend.dto.video.VideoTranscodeMessage;
import ITubeBackend.dto.video.VideoTranscodedEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON (de)serialisation of the events services exchange over Kafka. Every event carries a type and a schema
 * version, so a consumer can reject (and dead-letter) messages it doesn't understand instead of misreading them.
 * Malformed messages throw IllegalArgumentException, which the Kafka error handler never retries.
 */
public final class Events {
    private Events() {
    }

    public static String toJson(ObjectMapper objectMapper, Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialise " + event.getClass().getSimpleName(), exception);
        }
    }

    public static VideoDeletedEvent videoDeleted(ObjectMapper objectMapper, String payload) {
        VideoDeletedEvent event = read(objectMapper, payload, VideoDeletedEvent.class);
        require(VideoDeletedEvent.TYPE.equals(event.eventType()) && event.schemaVersion() == 1, payload);
        return event;
    }

    public static VideoTranscodedEvent videoTranscoded(ObjectMapper objectMapper, String payload) {
        VideoTranscodedEvent event = read(objectMapper, payload, VideoTranscodedEvent.class);
        require(VideoTranscodedEvent.TYPE.equals(event.eventType()) && event.schemaVersion() == 1
                && event.videoId() != null, payload);
        return event;
    }

    public static AccountCreatedEvent accountCreated(ObjectMapper objectMapper, String payload) {
        AccountCreatedEvent event = read(objectMapper, payload, AccountCreatedEvent.class);
        require(AccountCreatedEvent.TYPE.equals(event.eventType()) && event.schemaVersion() == 1
                && event.userId() != null, payload);
        return event;
    }

    /** Accepts JSON versions 1 and 2, and the bare video id that very old producers sent. */
    public static VideoTranscodeMessage videoTranscode(ObjectMapper objectMapper, String payload) {
        if (!payload.trim().startsWith("{")) {
            try {
                return new VideoTranscodeMessage(1, VideoTranscodeMessage.TYPE, Long.parseLong(payload.trim()), null);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid video transcode message: " + payload, exception);
            }
        }
        VideoTranscodeMessage message = read(objectMapper, payload, VideoTranscodeMessage.class);
        require(VideoTranscodeMessage.TYPE.equals(message.eventType())
                && (message.schemaVersion() == 1 || message.schemaVersion() == 2) && message.videoId() != null, payload);
        return message;
    }

    private static <T> T read(ObjectMapper objectMapper, String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid " + type.getSimpleName() + " message: " + payload, exception);
        }
    }

    private static void require(boolean valid, String payload) {
        if (!valid) throw new IllegalArgumentException("Unsupported message: " + payload);
    }
}
