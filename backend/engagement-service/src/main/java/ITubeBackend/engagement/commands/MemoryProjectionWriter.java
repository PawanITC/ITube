package ITubeBackend.engagement.commands;

import ITubeBackend.engagement.*;
import ITubeBackend.service.playlists.PlaylistService;
import ITubeBackend.service.playlists.AutomaticPlaylistService;

/** Exercises the same plans as production without requiring external services in unit/API tests. */
public record MemoryProjectionWriter(WatchHistoryStore history, LikeStore likes, SavedVideoStore saved,
                                     PlaylistService playlists, AutomaticPlaylistService automatic) implements ProjectionWriter {
    public void apply(EngagementCommand c, MutationPlan plan) {
        switch (c.action()) {
            case HISTORY_ADD -> history.put(new WatchHistoryStore.HistoryEntry(c.resourceId(), c.userId(), c.videoId(), c.occurredAt()));
            case HISTORY_REMOVE -> history.delete(c.userId(), c.resourceId());
            case HISTORY_CLEAR -> history.clear(c.userId());
            case LIKE -> likes.likeIfAbsent(c.userId(), c.videoId(), plan.itemTime());
            case UNLIKE -> likes.unlike(c.userId(), c.videoId());
            case SAVE -> saved.saveIfAbsent(c.userId(), c.videoId(), plan.itemTime());
            case UNSAVE -> saved.remove(c.userId(), c.videoId());
            case SAVED_CLEAR -> saved.clear(c.userId());
            case AUTOMATIC_REFRESH -> automatic.applyRefresh(plan.snapshot());
            default -> playlists.applyPrepared(plan.before(), plan.after());
        }
    }
}
