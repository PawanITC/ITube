package ITubeBackend.engagement.scylla;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Applies resources/scylla/engagement.cql (idempotent CREATE ... IF NOT EXISTS statements). */
public final class ScyllaSchema {
    private ScyllaSchema() {
    }

    public static void apply(CqlSession session, ScyllaProperties properties) {
        String script;
        try (InputStream in = ScyllaSchema.class.getResourceAsStream("/scylla/engagement.cql")) {
            if (in == null) throw new IllegalStateException("scylla/engagement.cql is missing from the classpath");
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        String withoutComments = Arrays.stream(script.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        for (String statement : withoutComments.split(";")) {
            String cql = statement.trim()
                    .replace("{keyspace}", properties.keyspace())
                    .replace("{replication}", properties.replication());
            if (cql.isEmpty()) continue;
            // Schema changes wait for every node to agree, which is slower than a normal request
            session.execute(SimpleStatement.newInstance(cql).setTimeout(Duration.ofSeconds(60)));
        }
    }
}
