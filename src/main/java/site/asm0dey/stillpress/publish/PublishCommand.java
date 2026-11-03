package site.asm0dey.stillpress.publish;

import jakarta.inject.Inject;
import module java.base;
import picocli.CommandLine.Command;

@Command(name = "publish", description = "Render the whole site into a new release and make it live")
public class PublishCommand implements Callable<Integer> {
    @Inject
    SitePublisher publisher;

    @Override
    public Integer call() {
        var r = publisher.publishAll();
        System.out.printf("Published %d files in %d ms%nRelease: %s%n", r.files(), r.took().toMillis(), r.release());
        return 0;
    }
}
