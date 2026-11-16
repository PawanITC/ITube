package ITubeBackend.dto.accounts;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 100) String firstname,
        @NotBlank @Size(max = 100) String lastname,
        @NotBlank @Email @Size(max = 320) String email,
        // BCrypt only uses the first 72 bytes, so longer passwords are rejected rather than silently truncated.
        @NotBlank @Size(min = 8, max = 72) String password,
        @Size(max = 255) String channelName) {
}
