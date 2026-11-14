package site.asm0dey.stillpress.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.prf.PrfSet;
import io.quarkus.runtime.ExecutionMode;
import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.PropertiesConfigSource;
import module java.base;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Supplies {@code quarkus.rest-csrf.token-signature-key} from the PRF keyset, below env and application.properties.
 */
public class KeysConfigSourceFactory implements ConfigSourceFactory {
    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        if (ExecutionMode.current() == ExecutionMode.UNSET) {
            // the build: generating keys there would leave them on the build machine and record the key in the jar
            return List.of();
        }
        var dataDir = value(context.getValue("stillpress.data-dir")).orElseThrow();
        var keys = Keys.get(Path.of(dataDir), value(context.getValue("stillpress.keys-dir")).map(Path::of));
        try {
            var key = keys
                .prf()
                .getPrimitive(RegistryConfiguration.get(), PrfSet.class)
                .computePrimary("csrf-token-signature".getBytes(UTF_8), 32);
            return List.of(
                    new PropertiesConfigSource(
                            Map.of("quarkus.rest-csrf.token-signature-key", HexFormat.of().formatHex(key)),
                            "stillpress-keys",
                            50
                    )
            );
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Optional<String> value(ConfigValue value) {
        return Optional
            .ofNullable(value)
            .map(ConfigValue::getValue)
            .filter(v -> !v.isBlank());
    }
}
