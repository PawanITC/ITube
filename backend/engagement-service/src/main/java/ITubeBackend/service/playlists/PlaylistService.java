package ITubeBackend.service.playlists;

import ITubeBackend.common.client.CatalogClient;
import ITubeBackend.common.client.DownstreamUnavailableException;
import ITubeBackend.dto.playlists.*;
import ITubeBackend.dto.video.VideoSummaryDto;
import ITubeBackend.engagement.PlaylistStore;
import ITubeBackend.engagement.PlaylistStore.Item;
import ITubeBackend.engagement.PlaylistStore.Playlist;
import ITubeBackend.engagement.TimeUuids;
import ITubeBackend.engagement.commands.EngagementCommand;
import ITubeBackend.entities.VideoVisibility;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

@Service
public class PlaylistService {
    public record PreparedPlaylist(Playlist before, Playlist after) { }

    /** Pure planning: the consumer persists this snapshot before attempting a conditional write. */
    public PreparedPlaylist prepare(EngagementCommand command) {
        long user = command.userId();
        var now = command.occurredAt();
        if (command.action() == EngagementCommand.Action.PLAYLIST_CREATE) {
            return new PreparedPlaylist(null, new Playlist(command.resourceId(), user, title(command.title()),
                    now, now, command.operationId(), List.of()));
        }
        Playlist before = owned(user, command.resourceId());
        String name = before.title();
        var items = new ArrayList<>(before.items());
        switch (command.action()) {
            case PLAYLIST_DELETE -> { return new PreparedPlaylist(before, null); }
            case PLAYLIST_RENAME -> name = title(command.title());
            case PLAYLIST_ADD -> {
                positive(command.videoId());
                var video = catalog.videoAccess(command.videoId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
                if (video.visibility() == VideoVisibility.PRIVATE && !Long.valueOf(user).equals(video.ownerUserId())) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
                }
                if (items.stream().noneMatch(item -> item.videoId() == command.videoId())) {
                    if (items.size() >= MAX_VIDEOS) throw new ResponseStatusException(HttpStatus.CONFLICT, "A playlist can contain up to 500 videos");
                    items.add(new Item(command.videoId(), now));
                }
            }
            case PLAYLIST_REMOVE -> { positive(command.videoId()); items.removeIf(item -> item.videoId() == command.videoId()); }
            case PLAYLIST_MOVE -> {
                positive(command.videoId());
                if (!before.version().equals(command.expectedVersion())) throw conflict();
                if (command.position() == null || command.position() < 0 || command.position() >= items.size()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Position is outside this playlist");
                }
                Item item = items.stream().filter(value -> value.videoId() == command.videoId()).findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video is not in this playlist"));
                items.remove(item); items.add(command.position(), item);
            }
            default -> throw new IllegalArgumentException("Not a playlist command");
        }
        if (name.equals(before.title()) && items.equals(before.items())) return new PreparedPlaylist(before, before);
        return new PreparedPlaylist(before, new Playlist(before.id(), user, name, before.createdAt(), now,
                command.operationId(), items));
    }

    /** Reuses the saved version after restart; a duplicate move cannot move the video a second time. */
    public void applyPrepared(Playlist before, Playlist after) {
        Playlist target = after == null ? before : after;
        var current = store.find(target.userId(), target.id());
        if (after == null) {
            if (current.isEmpty()) return;
            if (!current.get().version().equals(before.version()) || !store.delete(before)) throw conflict();
        } else if (before == null) {
            if (current.isPresent() && current.get().version().equals(after.version())) return;
            if (!store.create(after)) throw conflict();
        } else {
            if (current.isPresent() && current.get().version().equals(after.version())) return;
            if (current.isEmpty() || !current.get().version().equals(before.version()) || !store.replace(before, after)) throw conflict();
        }
    }

    public PlaylistResponse preparedResponse(Playlist value) { return response(value); }
    // Bounding the row keeps conditional order updates and playback queues small.
    public static final int MAX_VIDEOS = 500;
    private final PlaylistStore store;
    private final CatalogClient catalog;
    private final Clock clock;

    public PlaylistService(PlaylistStore store, CatalogClient catalog, Clock clock) {
        this.store = store;
        this.catalog = catalog;
        this.clock = clock;
    }

    public PlaylistResponse create(Long userId, String title) {
        requireUser(userId);
        var now = clock.instant();
        var playlist = new Playlist(TimeUuids.at(now), userId, title(title), now, now, UUID.randomUUID(), List.of());
        if (!store.create(playlist)) throw conflict();
        return response(playlist);
    }

    public List<PlaylistResponse> list(Long userId, int page, int size) {
        requireUser(userId);
        paging(page, size);
        try (var playlists = store.newestFirst(userId)) {
            return playlists.skip((long) page * size).limit(size).map(this::response).toList();
        }
    }

    public PlaylistDetailResponse detail(Long userId, UUID id, int page, int size) {
        Playlist playlist = owned(userId, id);
        paging(page, size);
        long offset = (long) page * size;
        List<Item> items = playlist.items().stream().skip(offset).limit(size).toList();
        Map<Long, VideoSummaryDto> videos;
        try {
            videos = items.isEmpty() ? Map.of() : catalog.visibleVideoSummaries(items.stream().map(Item::videoId).toList(), userId);
        } catch (DownstreamUnavailableException exception) {
            // As in History, keep the user's list usable when catalog-service is unavailable.
            videos = Map.of();
        }
        List<PlaylistVideoResponse> entries = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            entries.add(new PlaylistVideoResponse(item.videoId(), item.addedAt(), (int) offset + index, videos.get(item.videoId())));
        }
        return new PlaylistDetailResponse(response(playlist), entries, page, size, offset + items.size() < playlist.items().size());
    }

    public PlaylistResponse rename(Long userId, UUID id, String title) {
        String cleaned = title(title);
        return change(userId, id, current -> changed(current, cleaned, current.items()));
    }

    public PlaylistResponse addVideo(Long userId, UUID id, Long videoId) {
        owned(userId, id); // Check ownership before contacting catalog-service.
        positive(videoId);
        var video = catalog.videoAccess(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found"));
        if (video.visibility() == VideoVisibility.PRIVATE && !userId.equals(video.ownerUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found");
        }
        return change(userId, id, current -> {
            if (current.items().stream().anyMatch(item -> item.videoId() == videoId)) return current;
            if (current.items().size() >= MAX_VIDEOS) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A playlist can contain up to 500 videos");
            }
            var items = new ArrayList<>(current.items());
            items.add(new Item(videoId, clock.instant()));
            return changed(current, current.title(), items);
        });
    }

    public PlaylistResponse removeVideo(Long userId, UUID id, Long videoId) {
        positive(videoId);
        return change(userId, id, current -> {
            var items = current.items().stream().filter(item -> item.videoId() != videoId).toList();
            return items.size() == current.items().size() ? current : changed(current, current.title(), items);
        });
    }

    public PlaylistResponse moveVideo(Long userId, UUID id, Long videoId, int position, UUID version) {
        positive(videoId);
        Playlist current = owned(userId, id);
        if (!current.version().equals(version)) throw conflict();
        if (position < 0 || position >= current.items().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Position is outside this playlist");
        }
        var items = new ArrayList<>(current.items());
        Item item = items.stream().filter(entry -> entry.videoId() == videoId).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video is not in this playlist"));
        items.remove(item);
        items.add(position, item);
        Playlist next = changed(current, current.title(), items);
        if (!store.replace(current, next)) throw conflict();
        return response(next);
    }

    public void delete(Long userId, UUID id) {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (store.delete(owned(userId, id))) return;
        }
        throw conflict();
    }

    private PlaylistResponse change(Long userId, UUID id, UnaryOperator<Playlist> update) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Playlist current = owned(userId, id);
            Playlist next = update.apply(current);
            if (next == current || store.replace(current, next)) return response(next);
        }
        throw conflict();
    }

    private Playlist changed(Playlist value, String title, List<Item> items) {
        return new Playlist(value.id(), value.userId(), title, value.createdAt(), clock.instant(), UUID.randomUUID(), items);
    }

    private Playlist owned(Long userId, UUID id) {
        requireUser(userId);
        if (id == null || id.version() != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid playlist id");
        // User id is part of the database key: another user's playlist also appears as 404.
        return store.find(userId, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Playlist not found"));
    }

    private PlaylistResponse response(Playlist value) {
        return new PlaylistResponse(value.id(), value.title(), value.createdAt(), value.updatedAt(), value.items().size(), value.version());
    }

    private static String title(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Playlist title must contain 1 to 100 characters");
        }
        return value.strip();
    }

    private static void positive(Long id) {
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A positive video ID is required");
    }

    private static void requireUser(Long userId) {
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in first");
    }

    private static void paging(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be nonnegative and size must be 1 to 100");
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Playlist changed. Refresh it and try again.");
    }
}
