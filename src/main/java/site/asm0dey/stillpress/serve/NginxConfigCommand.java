package site.asm0dey.stillpress.serve;

import jakarta.inject.Inject;
import module java.base;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import site.asm0dey.stillpress.config.DataDir;

@Command(
        name = "nginx-config",
        description = "Print the nginx include for serving Mode 2; put it inside your server { } block"
)
public class NginxConfigCommand implements Callable<Integer> {
    @Option(names = "--root", description = "Site root as nginx sees it (default: the data directory's output/current)")
    Path root;
    @Option(
            names = "--upstream",
            defaultValue = "127.0.0.1:8080",
            description = "Stillpress host:port (default: ${DEFAULT-VALUE})"
    )
    String upstream;
    @Spec
    CommandSpec spec;
    @Inject
    DataDir dataDir;

    @Override
    public Integer call() {
        spec.commandLine().getOut().print(NginxConfig.render(root != null ? root : dataDir.current(), upstream));
        return 0;
    }
}
