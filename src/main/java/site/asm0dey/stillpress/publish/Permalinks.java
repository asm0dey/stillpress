package site.asm0dey.stillpress.publish;

import module java.base;

/**
 * Permalinks are stored decoded (UTF-8 text); {@link #encode} gives the on-the-wire form WordPress uses.
 */
public final class Permalinks {
    private Permalinks() {
    }

    /**
     * Decoded path of an absolute URL, {@code "/"} when it has none.
     */
    public static String decodedPath(String absoluteUrl) {
        var path = URI.create(absoluteUrl).getPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    /**
     * Percent-decodes UTF-8 ({@code +} stays a plus); text that is not valid percent-encoding is returned as is.
     */
    public static String decode(String encoded) {
        if (encoded == null || encoded.indexOf('%') < 0) {
            return encoded;
        }
        try {
            return URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return encoded;
        }
    }

    /**
     * Percent-encodes everything except RFC 3986 unreserved characters and {@code '/'}, with lowercase hex like WordPress.
     */
    public static String encode(String decodedPath) {
        var sb = new StringBuilder();
        for (byte b : decodedPath.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "-._~/".indexOf(c) >= 0) {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xf, 16));
            }
        }
        return sb.toString();
    }

    /**
     * {@code root/<path>/index.html}; refuses paths that escape {@code root}.
     */
    public static Path toFile(Path root, String decodedPath) {
        var base = root.toAbsolutePath().normalize();
        var dir = base.resolve(decodedPath.replaceFirst("^/+", "")).normalize();
        if (!dir.startsWith(base)) {
            throw new IllegalArgumentException("permalink escapes output root: " + decodedPath);
        }
        return dir.resolve("index.html");
    }
}
