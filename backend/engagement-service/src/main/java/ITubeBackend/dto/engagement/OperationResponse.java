package ITubeBackend.dto.engagement;

import java.util.UUID;

/** PENDING means broker-accepted/processing, SUCCEEDED means database writes have completed. */
public record OperationResponse(UUID operationId, String state, Object result, int httpStatus, String message) { }
