package site.asm0dey.stillpress.publish;

import jakarta.inject.Inject;
import module java.base;
import picocli.CommandLine.Command;

@Command(name = "publish", description = "Render the whole site into a new release and make it live")
public class PublishCommand implements Callable<Integer> {
    @Inject
    SitePublisher publisher;
    @Inject
    PublishLock lock;
    @Inject
    PublishQueue queue;

    /**
     * A full publish under the lock that consumes the queued rows too, so the server has nothing left to redo.
     */
    @Override
    public Integer call() {
        var r = lock.holding(() -> {
            var b = queue
                .snapshot()
                .map(s -> new PublishQueue.Batch(s.ids(), s.items(), s.bases(), s.media(), true))
                .orElse(new PublishQueue.Batch(Set.of(), Set.of(), Set.of(), false, true));
            var result = publisher.publish(b);
            queue.done(b);
            return result;
        });
        System.out.printf("Published %d files in %d ms%nRelease: %s%n", r.files(), r.took().toMillis(), r.release());
        return 0;
    }
}
