package site.asm0dey.stillpress.store;

import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import org.jooq.DSLContext;
import site.asm0dey.stillpress.db.DefaultSchema;

@ApplicationScoped
public class SchemaRepository {
    private final DSLContext db;

    SchemaRepository(DSLContext db) {
        this.db = db;
    }

    /**
     * Row count of every table, by table name, in schema order.
     */
    public SequencedMap<String, Integer> rowCounts() {
        var counts = new LinkedHashMap<String, Integer>();
        // one count per table: a dry run's report, not bulk work
        for (var t : DefaultSchema.DEFAULT_SCHEMA.getTables()) {
            counts.put(t.getName(), db.fetchCount(t));
        }
        return counts;
    }
}
