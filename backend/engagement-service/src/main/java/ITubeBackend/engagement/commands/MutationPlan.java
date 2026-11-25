package ITubeBackend.engagement.commands;

import ITubeBackend.engagement.PlaylistStore.Playlist;
import ITubeBackend.engagement.AutomaticPlaylistStore.Snapshot;
import java.time.Instant;

/** Persisted BEFORE applying: retries reuse the same timestamp, index key, playlist version and result. */
public record MutationPlan(long writeMicros, Instant itemTime, Playlist before, Playlist after, Snapshot snapshot, String result) { }
