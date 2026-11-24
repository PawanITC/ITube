package ITubeBackend.engagement.commands;

/** Only transport/schema/key errors may skip to the DLT; database/application failures must retain order. */
public class InvalidCommandEnvelopeException extends IllegalArgumentException {
    public InvalidCommandEnvelopeException(String message) { super(message); }
    public InvalidCommandEnvelopeException(String message, Throwable cause) { super(message, cause); }
}
