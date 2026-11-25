package ITubeBackend.engagement.commands;

import ITubeBackend.dto.history.HistoryResponse;
import ITubeBackend.engagement.*;
import ITubeBackend.service.likedvideos.LikedVideoService;
import ITubeBackend.service.savedvideos.SavedVideoService;
import ITubeBackend.service.playlists.PlaylistService;
import ITubeBackend.service.playlists.AutomaticPlaylistService;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;

@Component
public class MutationPlanner {
    private final LikeStore likes;
    private final SavedVideoStore saved;
    private final WatchHistoryStore history;
    private final LikedVideoService likeService;
    private final SavedVideoService savedService;
    private final PlaylistService playlists;
    private final AutomaticPlaylistService automatic;
    private final ObjectMapper json;
    public MutationPlanner(LikeStore likes, SavedVideoStore saved, WatchHistoryStore history,
            LikedVideoService likeService, SavedVideoService savedService, PlaylistService playlists,
            AutomaticPlaylistService automatic, ObjectMapper json) {
        this.likes = likes; this.saved = saved; this.history = history; this.likeService = likeService;
        this.savedService = savedService; this.playlists = playlists; this.json = json;
        this.automatic = automatic;
    }

    public void validate(EngagementCommand c) {
        if (c.schemaVersion() != 1 || c.operationId() == null || c.operationId().version() != 4
                || c.action() == null || c.userId() <= 0 || c.occurredAt() == null) throw new IllegalArgumentException("Invalid engagement envelope");
        switch (c.action()) {
            case HISTORY_ADD, LIKE, UNLIKE, SAVE, UNSAVE, PLAYLIST_ADD, PLAYLIST_REMOVE, PLAYLIST_MOVE -> {
                if (c.videoId() == null || c.videoId() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A positive video ID is required");
            }
            default -> { }
        }
        if (c.action() == EngagementCommand.Action.HISTORY_ADD || c.action() == EngagementCommand.Action.HISTORY_REMOVE
                || c.action().name().startsWith("PLAYLIST_")) {
            if (c.resourceId() == null || c.resourceId().version() != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid resource id");
        }
        if (c.action() == EngagementCommand.Action.LIKE) likeService.requireVisibleVideo(c.userId(), c.videoId());
        if (c.action() == EngagementCommand.Action.SAVE) savedService.validateSave(c.userId(), c.videoId());
        if (c.action() == EngagementCommand.Action.HISTORY_REMOVE && history.find(c.userId(), c.resourceId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "History entry not found");
        }
        if (c.action().name().startsWith("PLAYLIST_")) playlists.prepare(c);
    }

    public MutationPlan prepare(EngagementCommand c, long writeMicros) {
        validate(c);
        Instant itemTime = null;
        Object result = null;
        PlaylistService.PreparedPlaylist playlist = null;
        AutomaticPlaylistStore.Snapshot snapshot = null;
        switch (c.action()) {
            case HISTORY_ADD -> result = new HistoryResponse(c.resourceId(), c.videoId(), c.occurredAt(), null);
            case HISTORY_REMOVE -> {
                if (history.find(c.userId(), c.resourceId()).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "History entry not found");
            }
            case LIKE, UNLIKE -> itemTime = likes.find(c.userId(), c.videoId())
                    .map(LikeStore.Like::likedAt).orElse(c.action() == EngagementCommand.Action.LIKE ? c.occurredAt() : null);
            case SAVE, UNSAVE -> itemTime = saved.find(c.userId(), c.videoId())
                    .map(SavedVideoStore.SavedVideo::savedAt).orElse(c.action() == EngagementCommand.Action.SAVE ? c.occurredAt() : null);
            case AUTOMATIC_REFRESH -> {
                snapshot = automatic.prepareRefresh(c.userId(), c.operationId());
                result = automatic.preparedResponse(snapshot);
            }
            default -> {
                if (c.action().name().startsWith("PLAYLIST_")) {
                    playlist = playlists.prepare(c);
                    if (playlist.after() != null) result = playlists.preparedResponse(playlist.after());
                }
            }
        }
        return new MutationPlan(writeMicros, itemTime, playlist == null ? null : playlist.before(),
                playlist == null ? null : playlist.after(), snapshot, json.writeValueAsString(result));
    }
}
