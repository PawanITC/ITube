package ITubeBackend.engagement;

import com.datastax.oss.driver.api.core.uuid.Uuids;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Version 1 (time-based) UUIDs, the CQL timeuuid type: they sort by time and are unique across nodes. */
public final class TimeUuids {
    // The UUID epoch (1582-10-15) as Unix milliseconds; UUID timestamps count 100ns intervals from it
    private static final long UUID_EPOCH_MILLIS = -12219292800000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TimeUuids() {
    }

    /**
     * For "now" this uses the driver's generator, which also orders ids created in the same millisecond.
     * For other times (tests, migrated rows) it builds an id at that instant with random low bits.
     */
    public static UUID at(Instant time) {
        if (Duration.between(time, Instant.now()).abs().compareTo(Duration.ofSeconds(1)) < 0) {
            return Uuids.timeBased();
        }
        return at(time, RANDOM.nextLong());
    }

    /** Deterministic: the same time and uniquifier always give the same id, so copies can be re-run safely. */
    public static UUID at(Instant time, long uniquifier) {
        long ticks = (time.toEpochMilli() - UUID_EPOCH_MILLIS) * 10_000L + (time.getNano() % 1_000_000) / 100;
        long msb = ((ticks & 0xFFFFFFFFL) << 32) | (((ticks >>> 32) & 0xFFFFL) << 16) | 0x1000L | ((ticks >>> 48) & 0x0FFFL);
        long lsb = (uniquifier & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    public static Instant instantOf(UUID timeUuid) {
        return Instant.ofEpochMilli(Uuids.unixTimestamp(timeUuid));
    }
}
