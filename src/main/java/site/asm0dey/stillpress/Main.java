package site.asm0dey.stillpress;

import io.quarkus.picocli.runtime.PicocliRunner;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Only {@code serve} (also the default command) binds the HTTP port; {@code import}, {@code publish} and
 * {@code verify-urls} run beside a live server instead of failing with "port in use".
 */
@QuarkusMain
public class Main {
    public static void main(String... args) {
        if (!serves(args)) {
            // runtime config, read when Quarkus starts below; the router still exists, nothing listens
            System.setProperty("quarkus.http.host-enabled", "false");
        }
        Quarkus.run(PicocliRunner.class, args);
    }

    static boolean serves(String... args) {
        return args.length == 0 || args[0].equals("serve");
    }
}
