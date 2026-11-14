package site.asm0dey.stillpress.config;

import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.prf.PrfConfig;
import module java.base;

/**
 * The two Tink keysets: AEAD for stored secrets and PRF for derived keys. They live in {@code <data-dir>/keys/},
 * generated there on first use, or only read from an override directory ({@code stillpress.keys-dir}).
 */
public final class Keys {
    private static final String AEAD = "aead.json";
    private static final String PRF = "prf.json";
    private static final Map<Path, Keys> LOADED = new ConcurrentHashMap<>();

    static {
        try {
            AeadConfig.register();
            PrfConfig.register();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private final Path dir;
    private final KeysetHandle aead;
    private final KeysetHandle prf;
    private final boolean generatedNow;

    private Keys(Path dir, KeysetHandle aead, KeysetHandle prf, boolean generatedNow) {
        this.dir = dir;
        this.aead = aead;
        this.prf = prf;
        this.generatedNow = generatedNow;
    }

    /**
     * Memoized per key directory: config and CDI share one instance, while tests restart with other data dirs.
     */
    public static Keys get(Path dataDir, Optional<Path> overrideDir) {
        return LOADED.computeIfAbsent(dir(dataDir, overrideDir), _ -> load(dataDir, overrideDir));
    }

    static Keys load(Path dataDir, Optional<Path> overrideDir) {
        var dir = dir(dataDir, overrideDir);
        var aeadFile = dir.resolve(AEAD);
        var prfFile = dir.resolve(PRF);
        try {
            if (Files.exists(aeadFile) && Files.exists(prfFile)) {
                return new Keys(dir, read(aeadFile), read(prfFile), false);
            }
            if (overrideDir.isPresent() || Files.exists(aeadFile) || Files.exists(prfFile)) {
                throw new IllegalStateException("No Tink keysets in " + dir + ": expected aead.json and prf.json");
            }
            var aead = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"));
            var prf = KeysetHandle.generateNew(KeyTemplates.get("HMAC_SHA256_PRF"));
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            write(aeadFile, aead);
            write(prfFile, prf);
            return new Keys(dir, aead, prf, true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unreadable Tink keyset in " + dir, e);
        }
    }

    private static Path dir(Path dataDir, Optional<Path> overrideDir) {
        return overrideDir
            .orElseGet(() -> dataDir.resolve("keys"))
            .toAbsolutePath()
            .normalize();
    }

    private static KeysetHandle read(Path file) throws IOException, GeneralSecurityException {
        return TinkJsonProtoKeysetFormat.parseKeyset(
                Files.readString(file),
                RegistryConfiguration.get(),
                InsecureSecretKeyAccess.get()
        );
    }

    private static void write(Path file, KeysetHandle handle) throws IOException, GeneralSecurityException {
        // owner-only before the key material goes in; the rename makes the file appear whole
        var temp = Files.createTempFile(
                file.getParent(),
                file.getFileName().toString(),
                ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        );
        Files.writeString(
                temp,
                TinkJsonProtoKeysetFormat.serializeKeyset(
                        handle,
                        RegistryConfiguration.get(),
                        InsecureSecretKeyAccess.get()
                )
        );
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
    }

    public KeysetHandle aead() {
        return aead;
    }

    public KeysetHandle prf() {
        return prf;
    }

    /**
     * True when this process created the keyset files.
     */
    public boolean generatedNow() {
        return generatedNow;
    }

    public Path dir() {
        return dir;
    }

    /**
     * Deletes the files this process generated, so the next start does not take them for the real keys.
     */
    public void discardGenerated() {
        if (!generatedNow) {
            return;
        }
        try {
            Files.deleteIfExists(dir.resolve(AEAD));
            Files.deleteIfExists(dir.resolve(PRF));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        LOADED.remove(dir, this);
    }
}
