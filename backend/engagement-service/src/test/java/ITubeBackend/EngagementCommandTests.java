package ITubeBackend;

import ITubeBackend.common.client.RecommendationCatalogClient;
import ITubeBackend.controller.*;
import ITubeBackend.engagement.*;
import ITubeBackend.engagement.commands.*;
import ITubeBackend.engagement.commands.EngagementCommand.Action;
import ITubeBackend.engagement.memory.*;
import ITubeBackend.entities.VideoVisibility;
import ITubeBackend.service.history.HistoryService;
import ITubeBackend.service.likedvideos.LikedVideoService;
import ITubeBackend.service.savedvideos.SavedVideoService;
import ITubeBackend.service.playlists.*;
import ITubeBackend.support.FakeCatalog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class EngagementCommandTests {
    final Clock clock = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC);
    final ObjectMapper json = new ObjectMapper();
    final MemoryOperationStore operations = new MemoryOperationStore();
    final InMemoryEngagementStores.History history = new InMemoryEngagementStores.History();
    final InMemoryEngagementStores.Likes likes = new InMemoryEngagementStores.Likes();
    final InMemorySavedVideoStore saved = new InMemorySavedVideoStore();
    final InMemoryPlaylistStore playlists = new InMemoryPlaylistStore();
    final FakeCatalog catalog = new FakeCatalog();
    final RecommendationCatalogClient recommendations = mock(RecommendationCatalogClient.class);
    final PlaylistService playlistService = new PlaylistService(playlists, catalog, clock);
    final AutomaticPlaylistService automatic = new AutomaticPlaylistService(history, likes, catalog, recommendations,
            new InMemoryAutomaticPlaylistStore(clock), clock);
    final MutationPlanner planner = new MutationPlanner(likes, saved, history, new LikedVideoService(likes, catalog, clock),
            new SavedVideoService(saved, catalog, clock), playlistService, automatic, json);
    final ProjectionWriter writer = new MemoryProjectionWriter(history, likes, saved, playlistService, automatic);
    final CommandProcessor processor = processor(writer, operations);
    long video;

    @BeforeEach void setup() {
        video = catalog.add(2L, "Java Kafka", VideoVisibility.PUBLIC).getVideoId();
        when(recommendations.candidates(any())).thenReturn(List.of());
        when(recommendations.eligibleVideos(any())).thenReturn(List.of());
    }
    CommandProcessor processor(ProjectionWriter projection, OperationStore inbox) {
        return new CommandProcessor(inbox, planner, projection, clock, new SimpleMeterRegistry());
    }
    EngagementCommand command(Action action, UUID resource) {
        return new EngagementCommand(1, UUID.randomUUID(), 1L, action,
                switch (action) { case HISTORY_ADD, LIKE, UNLIKE, SAVE, UNSAVE, PLAYLIST_ADD, PLAYLIST_REMOVE, PLAYLIST_MOVE -> video; default -> null; },
                resource, action == Action.PLAYLIST_CREATE || action == Action.PLAYLIST_RENAME ? "Learning" : null,
                null, null, clock.instant());
    }
    UUID id() { return TimeUuids.at(clock.instant()); }
    OperationStore.Operation receipt(EngagementCommand c) { return operations.find(c.userId(), c.operationId()).orElseThrow(); }

    @Test void historyRedeliveryIsOneEntryButTwoViewingSessionsAreTwoEntries() {
        var first = command(Action.HISTORY_ADD, id());
        processor.process(first); processor.process(first);
        assertThat(history.page(1, 0, 20)).hasSize(1);
        processor.process(command(Action.HISTORY_ADD, id()));
        assertThat(history.page(1, 0, 20)).hasSize(2);
        assertThat(receipt(first).state()).isEqualTo("SUCCEEDED");
    }

    @Test void clearAndRemoveCannotBeUndoneByReplayingOldHistoryEvents() {
        var first = command(Action.HISTORY_ADD, id()); processor.process(first);
        processor.process(command(Action.HISTORY_REMOVE, first.resourceId())); processor.process(first);
        assertThat(history.page(1, 0, 20)).isEmpty();
        var next = command(Action.HISTORY_ADD, id()); processor.process(next);
        processor.process(command(Action.HISTORY_CLEAR, null)); processor.process(next);
        assertThat(history.page(1, 0, 20)).isEmpty();
    }

    @Test void likesAndSavesKeepOriginalDatesAndRespectRemovalAndClear() {
        for (Action add : List.of(Action.LIKE, Action.SAVE)) {
            var first = command(add, null); processor.process(first); processor.process(first);
            processor.process(command(add, null));
            assertThat(add == Action.LIKE ? likes.newestFirst(1).count() : saved.newestFirst(1).count()).isEqualTo(1);
            processor.process(command(add == Action.LIKE ? Action.UNLIKE : Action.UNSAVE, null));
            processor.process(first);
            assertThat(add == Action.LIKE ? likes.isLiked(1, video) : saved.isSaved(1, video)).isFalse();
        }
        var save = command(Action.SAVE, null); processor.process(save);
        processor.process(command(Action.SAVED_CLEAR, null)); processor.process(save);
        assertThat(saved.isSaved(1, video)).isFalse();
    }

    @Test void crashAfterWritingBeforeReceiptReusesPreparedIdAndPlan() {
        var once = new AtomicBoolean();
        var unstable = processor((command, plan) -> {
            writer.apply(command, plan);
            if (!once.getAndSet(true)) throw new IllegalStateException("simulated crash before receipt");
        }, operations);
        var command = command(Action.HISTORY_ADD, id());
        assertThatThrownBy(() -> unstable.process(command)).isInstanceOf(IllegalStateException.class);
        assertThat(receipt(command).state()).isEqualTo("PENDING");
        MutationPlan plan = receipt(command).plan();
        processor.process(command);
        assertThat(history.page(1, 0, 20)).hasSize(1);
        assertThat(receipt(command).plan()).isEqualTo(plan);
    }

    @Test void playlistCreateMoveAndDeleteAreSafeAfterConsumerRestart() {
        var create = command(Action.PLAYLIST_CREATE, id()); processor.process(create); processor.process(create);
        processor.process(command(Action.PLAYLIST_ADD, create.resourceId()));
        long second = catalog.add(2L, "Second", VideoVisibility.PUBLIC).getVideoId();
        var add = new EngagementCommand(1, UUID.randomUUID(), 1, Action.PLAYLIST_ADD, second,
                create.resourceId(), null, null, null, clock.instant());
        processor.process(add);
        UUID before = playlists.find(1, create.resourceId()).orElseThrow().version();
        var move = new EngagementCommand(1, UUID.randomUUID(), 1, Action.PLAYLIST_MOVE, second,
                create.resourceId(), null, 0, before, clock.instant());
        var unstable = processor((command, plan) -> { writer.apply(command, plan); throw new IllegalStateException("restart"); }, operations);
        assertThatThrownBy(() -> unstable.process(move)).isInstanceOf(IllegalStateException.class);
        processor.process(move);
        assertThat(playlists.find(1, create.resourceId()).orElseThrow().items()).extracting(PlaylistStore.Item::videoId)
                .containsExactly(second, video);
        processor.process(command(Action.PLAYLIST_DELETE, create.resourceId()));
        processor.process(create); processor.process(add); processor.process(move);
        assertThat(playlists.find(1, create.resourceId())).isEmpty();
    }

    @Test void automaticRefreshRetainsOneSnapshotAndDoesNotRegenerateOnRedelivery() {
        var refresh = command(Action.AUTOMATIC_REFRESH, null);
        processor.process(refresh); processor.process(refresh);
        assertThat(automatic.snapshot(1L, refresh.operationId()).playlist().id()).isEqualTo(refresh.operationId());
        verify(recommendations, times(1)).candidates(any());
    }

    @Test void privateVideosAreRejectedAndInfrastructureFailuresLeavePendingForRetry() {
        var like = command(Action.LIKE, null);
        catalog.setDown(true);
        assertThatThrownBy(() -> processor.process(like)).isInstanceOf(RuntimeException.class);
        assertThat(receipt(like).state()).isEqualTo("PENDING");
        catalog.setDown(false); catalog.delete(video);
        processor.process(like);
        assertThat(receipt(like).state()).isEqualTo("FAILED");
        assertThat(receipt(like).httpStatus()).isEqualTo(404);
        assertThat(likes.isLiked(1, video)).isFalse();
    }

    @Test void schemaAndUserKeyAreValidatedBeforeTouchingTheDatabase() {
        var listener = new EngagementCommandListener(json, processor);
        assertThatThrownBy(() -> listener.consume(new ConsumerRecord<>("test", 0, 0, "1", "broken")))
                .isInstanceOf(IllegalArgumentException.class);
        var command = command(Action.HISTORY_ADD, id());
        assertThatThrownBy(() -> listener.consume(new ConsumerRecord<>("test", 0, 0, "2", json.writeValueAsString(command))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(operations.find(1, command.operationId())).isEmpty();
    }

    @Test void acceptanceDoesNotPretendTheConsumerAlreadyWroteAndReceiptsAreUserScoped() {
        List<EngagementCommand> queue = new ArrayList<>();
        var gateway = new CommandGateway(queue::add, planner, operations, clock, json);
        var response = gateway.submit(1, UUID.randomUUID().toString(), Action.HISTORY_ADD, video, null, null, null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getLocation().toString()).startsWith("/api/engagement/operations/");
        assertThat(history.page(1, 0, 20)).isEmpty();
        assertThat(gateway.status(1, response.getBody().operationId()).state()).isEqualTo("PENDING");
        processor.process(queue.getFirst());
        assertThat(gateway.status(1, response.getBody().operationId()).state()).isEqualTo("SUCCEEDED");
        assertThat(gateway.status(2, response.getBody().operationId()).state()).isEqualTo("PENDING");
    }

    @Test void idempotencyKeysCannotBeReusedForDifferentCommandsAndFailedSendsAreNotAccepted() {
        String key = UUID.randomUUID().toString();
        var gateway = new CommandGateway(processor::process, planner, operations, clock, json);
        gateway.submit(1, key, Action.LIKE, video, null, null, null, null);
        assertThatThrownBy(() -> gateway.submit(1, key, Action.UNLIKE, video, null, null, null, null))
                .isInstanceOf(ResponseStatusException.class);
        var failure = new CommandGateway(c -> { throw new IllegalStateException("Kafka down"); }, planner, operations, clock, json);
        assertThatThrownBy(() -> failure.submit(1, UUID.randomUUID().toString(), Action.HISTORY_ADD, video, null, null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void writeClockIsMonotonicWhenAnotherPodHasAnOlderClock() {
        assertThat(operations.nextWriteMicros(1, 100)).isEqualTo(100);
        assertThat(operations.nextWriteMicros(1, 90)).isEqualTo(101);
        assertThat(operations.nextWriteMicros(1, 300)).isEqualTo(300);
    }

    @Test void retryAfterUnknownPublishOutcomeUsesTheOriginalEnvelopeAndDoesNotDuplicateHistory() {
        String key = UUID.randomUUID().toString();
        List<EngagementCommand> sent = new ArrayList<>();
        var fail = new AtomicBoolean(true);
        var gateway = new CommandGateway(c -> {
            sent.add(c);
            if (fail.getAndSet(false)) throw new IllegalStateException("Acknowledgement lost");
            processor.process(c);
        }, planner, operations, clock, json);
        assertThatThrownBy(() -> gateway.submit(1, key, Action.HISTORY_ADD, video, null, null, null, null))
                .isInstanceOf(IllegalStateException.class);
        gateway.submit(1, key, Action.HISTORY_ADD, video, null, null, null, null);
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0)).isEqualTo(sent.get(1));
        assertThat(history.page(1, 0, 20)).hasSize(1);
    }

    @Test void failedKafkaAcknowledgementReturns503AndNeverClaimsSuccess() {
        @SuppressWarnings("unchecked") var kafka = (org.springframework.kafka.core.KafkaTemplate<String, String>) mock(org.springframework.kafka.core.KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Kafka offline")));
        var publisher = new KafkaCommandTransport(kafka, json, "commands");
        assertThatThrownBy(() -> publisher.publish(command(Action.LIKE, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
    }

    @Test void twoRequestsCannotReserveTheSameKeyForDifferentActions() throws Exception {
        var sent = new java.util.concurrent.CopyOnWriteArrayList<EngagementCommand>();
        var gateway = new CommandGateway(sent::add, planner, operations, clock, json);
        String key = UUID.randomUUID().toString();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var outcomes = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (Action action : List.of(Action.LIKE, Action.UNLIKE)) outcomes.add(pool.submit(() -> {
                try { return gateway.submit(1, key, action, video, null, null, null, null).getStatusCode().value(); }
                catch (ResponseStatusException error) { return error.getStatusCode().value(); }
            }));
            assertThat(List.of(outcomes.get(0).get(), outcomes.get(1).get())).containsExactlyInAnyOrder(202, 409);
            assertThat(sent).hasSize(1);
        }
    }
}
