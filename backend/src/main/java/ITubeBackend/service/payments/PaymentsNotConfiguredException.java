package ITubeBackend.service.payments;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class PaymentsNotConfiguredException extends ResponseStatusException {
    public PaymentsNotConfiguredException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "Payments are not configured on this server");
    }
}
