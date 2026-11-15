package site.asm0dey.stillpress.account;

import io.nayuki.qrcodegen.QrCode;
import module java.base;

/**
 * A QR code as an SVG string, drawn on the server so the secret never goes to a third party or a client library.
 */
public final class Qr {
    private static final int BORDER = 4;

    private Qr() {
    }

    public static String svg(String text) {
        var qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        var path = new StringBuilder();
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    path.append("M").append(x + BORDER).append(",").append(y + BORDER).append("h1v1h-1z");
                }
            }
        }
        var side = qr.size + 2 * BORDER;
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 "
                + side
                + " "
                + side
                + "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>"
                + "<path d=\""
                + path
                + "\" fill=\"#000000\"/></svg>";
    }
}
