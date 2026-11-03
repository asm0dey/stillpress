package site.asm0dey.stillpress.verify;

import module java.base;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "verify-urls", description = "Compare every URL of the live WordPress site against Stillpress")
public class VerifyUrlsCommand implements Callable<Integer> {
    @Option(names = "--source", required = true, description = "Live WordPress site, e.g. https://example.com")
    URI source;
    @Option(names = "--target", required = true, description = "Stillpress site, e.g. http://localhost:8080")
    URI target;
    @Option(names = "--max", defaultValue = "20000", description = "Maximum URLs to check")
    int max;

    @Override
    public Integer call() {
        var report = new UrlParityChecker().check(source, target, max);
        System.out.print(report.toText());
        return report.ok() ? 0 : 1;
    }
}
