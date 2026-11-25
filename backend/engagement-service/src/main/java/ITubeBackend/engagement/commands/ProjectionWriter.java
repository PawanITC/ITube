package ITubeBackend.engagement.commands;

/** Applies an immutable plan. Safe to retry after a crash between projection writes and the receipt. */
public interface ProjectionWriter {
    void apply(EngagementCommand command, MutationPlan plan);
}
