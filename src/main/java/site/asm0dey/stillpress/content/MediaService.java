package site.asm0dey.stillpress.content;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import javax.imageio.ImageIO;
import module java.base;
import site.asm0dey.stillpress.config.DataDir;
import site.asm0dey.stillpress.db.tables.records.MediaRecord;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.MediaRepository;

/**
 * Stores uploaded files under {@code <data>/media/YYYY/MM/}. Never svg, html, js or xml: they would run as script on
 * the site's origin. The MIME type comes from the allow-listed extension, never from the client.
 */
@ApplicationScoped
public class MediaService {
    static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip")
    );
    private static final Logger LOG = Logger.getLogger(MediaService.class);
    @Inject
    TransactionSynchronizationRegistry registry;
    @Inject
    MediaRepository media;
    @Inject
    DataDir dataDir;
    @Inject
    Permalinker permalinker;
    @Inject
    PublishQueue queue;
    @Inject
    Jobs jobs;
    @Inject
    ImportGuard guard;

    /**
     * Moves {@code upload} into the media folder of the current month (site time zone) under a sanitized, unique name.
     *
     * @throws IllegalArgumentException {@code type_not_allowed} for an extension outside the allow list
     */
    @Transactional
    public MediaRecord store(String fileName, Path upload, long userId) {
        guard.close();
        var last = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
        int dot = last.lastIndexOf('.');
        var ext = dot < 0 ? "" : last.substring(dot + 1).toLowerCase(Locale.ROOT);
        var mime = MIME.get(ext);
        if (mime == null) {
            throw new IllegalArgumentException("type_not_allowed");
        }
        var slug = Slugs.title(dot < 0 ? last : last.substring(0, dot));
        var base = slug.isBlank() ? "file" : slug;
        var month = YearMonth.now(permalinker.zone());
        var folder = "%d/%02d".formatted(month.getYear(), month.getMonthValue());
        try {
            var dir = Files.createDirectories(dataDir.media().resolve(folder));
            for (int n = 1; ; n++) {
                var name = base + (n == 1 ? "" : "-" + n) + "." + ext;
                try {
                    // O_EXCL reserves the name; a bare move would silently replace a file a parallel upload just placed
                    var target = Files.createFile(dir.resolve(name));
                    removeOnRollback(target);
                    var size = Files.size(upload);
                    var dims = dimensions(upload, mime);
                    Files.move(upload, target, StandardCopyOption.REPLACE_EXISTING);
                    var url = "/media/" + folder + "/" + name;
                    var r = media.insert(url, mime, dims[0], dims[1], size, userId, LocalDateTime.now(ZoneOffset.UTC));
                    queue.media();
                    jobs.requestPublish();
                    return r;
                } catch (FileAlreadyExistsException clash) {
                    // try the next suffix
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A file placed under media/ is published whether or not it has a library row, so a failed transaction removes it.
     */
    private void removeOnRollback(Path target) {
        registry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {}

            @Override
            public void afterCompletion(int status) {
                if (status != Status.STATUS_COMMITTED) {
                    try {
                        Files.deleteIfExists(target);
                    } catch (IOException e) {
                        LOG.warnf(e, "Could not remove %s after a rolled-back upload", target);
                    }
                }
            }
        });
    }

    @Transactional
    public MediaRecord updateMeta(long id, String title, String altText) {
        guard.close();
        media
            .findById(id)
            .orElseThrow(() -> new ItemService.Refused(404, "not_found", null));
        media.updateMeta(id, title, altText);
        return media.findById(id).orElseThrow();
    }

    /**
     * Width and height from the image header only; null when no reader knows the format (AVIF, WebP, non-images).
     */
    private static Integer[] dimensions(Path file, String mime) throws IOException {
        if (!mime.startsWith("image/")) {
            return new Integer[2];
        }
        try (var in = ImageIO.createImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return new Integer[2];
            }
            var reader = readers.next();
            try {
                reader.setInput(in);
                return new Integer[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        }
    }
}
