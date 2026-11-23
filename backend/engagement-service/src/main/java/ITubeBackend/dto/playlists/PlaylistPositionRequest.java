package ITubeBackend.dto.playlists;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** The version ensures a move is applied to the order the user actually saw. Positions start at zero. */
public record PlaylistPositionRequest(@NotNull @Min(0) @Max(499) Integer position, @NotNull UUID version) { }
