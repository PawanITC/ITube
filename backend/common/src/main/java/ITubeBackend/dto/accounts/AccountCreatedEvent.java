package ITubeBackend.dto.accounts;

/**
 * identity-service -> catalog-service (topic account.created, key = user id): create the account's first
 * channel. Registration also asks the catalog directly so the response can include the channel; this event
 * makes sure the channel still appears if that call failed. The catalog creates it at most once.
 */
public record AccountCreatedEvent(int schemaVersion, String eventType, Long userId, String channelName) {
    public static final String TYPE = "AccountCreated";

    public AccountCreatedEvent(Long userId, String channelName) {
        this(1, TYPE, userId, channelName);
    }
}
