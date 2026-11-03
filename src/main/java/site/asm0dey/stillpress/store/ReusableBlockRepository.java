package site.asm0dey.stillpress.store;

import static site.asm0dey.stillpress.db.Tables.REUSABLE_BLOCK;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import org.jooq.DSLContext;

@ApplicationScoped
public class ReusableBlockRepository {
    @Inject
    DSLContext db;

    /**
     * Content for a {@code wp:block} ref; imported markup refers to the WordPress post id, i.e. {@code source_id}.
     */
    public Optional<String> content(long id) {
        return db
            .select(REUSABLE_BLOCK.CONTENT)
            .from(REUSABLE_BLOCK)
            .where(REUSABLE_BLOCK.SOURCE_ID.eq(id))
            .fetchOptional(REUSABLE_BLOCK.CONTENT);
    }
}
