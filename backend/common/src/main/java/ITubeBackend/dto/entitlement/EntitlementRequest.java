package ITubeBackend.dto.entitlement;

import ITubeBackend.dto.video.VideoAccessDto;

/** Internal: catalog asks payments whether {@code userId} (null = signed out) may play {@code video}. */
public record EntitlementRequest(Long userId, VideoAccessDto video) {
}
