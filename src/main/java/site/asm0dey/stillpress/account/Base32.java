package site.asm0dey.stillpress.account;

import module java.base;

/**
 * RFC 4648 Base32, unpadded: the JDK has no encoder and authenticator apps want this alphabet.
 */
public final class Base32 {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    public static String encode(byte[] data) {
        var out = new StringBuilder((data.length * 8 + 4) / 5);
        var acc = 0;
        var bits = 0;
        for (byte b : data) {
            acc = acc << 8 | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt(acc >> (bits - 5) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt(acc << (5 - bits) & 31));
        }
        return out.toString();
    }
}
