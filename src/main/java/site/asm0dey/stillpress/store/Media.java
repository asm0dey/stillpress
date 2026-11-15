package site.asm0dey.stillpress.store;

public record Media(
        long id,
        String path,
        String mimeType,
        Integer width,
        Integer height,
        String title,
        String altText,
        Long sizeBytes,
        Long uploadedBy
) {}
