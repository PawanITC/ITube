package ITubeBackend;

import ITubeBackend.engagement.TimeUuids;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TimeUuidsTest {
    @Test
    void timeUuidsCarryTheirTimeAndAreRepeatableForCopies() {
        Instant time = Instant.parse("2026-01-02T10:00:00.123Z");
        var id = TimeUuids.at(time, 99L);
        assertThat(id.version()).isEqualTo(1);
        assertThat(Uuids.unixTimestamp(id)).isEqualTo(time.toEpochMilli());
        assertThat(TimeUuids.at(time, 99L)).isEqualTo(id);
        assertThat(TimeUuids.at(time, 100L)).isNotEqualTo(id);
        assertThat(TimeUuids.at(Instant.now()).version()).isEqualTo(1);
    }
}
