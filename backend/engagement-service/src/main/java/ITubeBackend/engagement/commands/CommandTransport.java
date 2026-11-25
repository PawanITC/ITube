package ITubeBackend.engagement.commands;

public interface CommandTransport {
    void publish(EngagementCommand command);
}
