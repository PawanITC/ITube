package ITubeBackend.engagement.commands;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

public class ScyllaOperationStore implements OperationStore {
    private final CqlSession session;
    private final ObjectMapper json;
    private final PreparedStatement insert, select, prepare, finish, clockInsert, clockSelect, clockUpdate;
    public ScyllaOperationStore(CqlSession session, String keyspace, ObjectMapper json) {
        this.session = session; this.json = json;
        String table = keyspace + ".engagement_operations";
        insert = session.prepare("INSERT INTO " + table + " (user_id, operation_id, command, state) VALUES (?, ?, ?, 'PENDING') IF NOT EXISTS");
        select = session.prepare("SELECT * FROM " + table + " WHERE user_id = ? AND operation_id = ?");
        prepare = session.prepare("UPDATE " + table + " SET plan = ? WHERE user_id = ? AND operation_id = ? IF plan = null");
        finish = session.prepare("UPDATE " + table + " SET state = ?, http_status = ?, message = ? WHERE user_id = ? AND operation_id = ? IF state = 'PENDING'");
        String clock = keyspace + ".engagement_command_clocks";
        clockInsert = session.prepare("INSERT INTO " + clock + " (user_id, write_micros) VALUES (?, ?) IF NOT EXISTS");
        clockSelect = session.prepare("SELECT write_micros FROM " + clock + " WHERE user_id = ?");
        clockUpdate = session.prepare("UPDATE " + clock + " SET write_micros = ? WHERE user_id = ? IF write_micros = ?");
    }
    public Operation register(EngagementCommand c) {
        session.execute(insert.bind(c.userId(), c.operationId(), json.writeValueAsString(c)));
        return find(c.userId(), c.operationId()).orElseThrow();
    }
    public Optional<Operation> find(long user, UUID id) {
        Row row = session.execute(select.bind(user, id).setConsistencyLevel(DefaultConsistencyLevel.LOCAL_SERIAL)).one();
        if (row == null) return Optional.empty();
        String plan = row.getString("plan");
        return Optional.of(new Operation(json.readValue(row.getString("command"), EngagementCommand.class),
                row.getString("state"), plan == null ? null : json.readValue(plan, MutationPlan.class),
                row.getInt("http_status"), row.getString("message")));
    }
    public MutationPlan prepare(EngagementCommand c, MutationPlan plan) {
        session.execute(prepare.bind(json.writeValueAsString(plan), c.userId(), c.operationId()));
        return find(c.userId(), c.operationId()).orElseThrow().plan();
    }
    public void finish(EngagementCommand c, String state, int status, String message) {
        session.execute(finish.bind(state, status, message, c.userId(), c.operationId()));
    }
    public long nextWriteMicros(long user, long wall) {
        if (session.execute(clockInsert.bind(user, wall)).wasApplied()) return wall;
        for (int attempt = 0; attempt < 20; attempt++) {
            long old = session.execute(clockSelect.bind(user).setConsistencyLevel(DefaultConsistencyLevel.LOCAL_SERIAL))
                    .one().getLong("write_micros");
            long next = Math.max(wall, old + 1);
            if (session.execute(clockUpdate.bind(next, user, old)).wasApplied()) return next;
        }
        throw new IllegalStateException("Engagement write clock is busy; retry this record");
    }
}
