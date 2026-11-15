package site.asm0dey.stillpress.store;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerBuilder;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.jdbc.AutodetectJdbcCustomization;
import com.github.kagkarlsson.scheduler.task.Task;
import jakarta.enterprise.context.ApplicationScoped;
import module java.base;
import javax.sql.DataSource;

/**
 * db-scheduler's {@code scheduled_tasks} table: builders bound to the datasource.
 */
@ApplicationScoped
public class TaskStore {
    private final DataSource dataSource;

    TaskStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public SchedulerClient.Builder client(Task<?>... knownTasks) {
        return SchedulerClient.Builder
            .create(dataSource, knownTasks)
            // the client builder has no alwaysPersistTimestampInUTC(); this is what that option configures
            .jdbcCustomization(new AutodetectJdbcCustomization(dataSource, true));
    }

    public SchedulerBuilder scheduler(Task<?>... knownTasks) {
        return Scheduler.create(dataSource, knownTasks);
    }
}
