package site.asm0dey.stillpress;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

/**
 * Test helper: a request carrying the CSRF token the way the admin UI sends it, as the {@code csrf-token} cookie and
 * the same value in {@code X-CSRF-TOKEN}.
 */
public final class Csrf {
    /**
     * 16 bytes, base64url without padding: the size quarkus-rest-csrf accepts
     */
    public static final String TOKEN = "c3RpbGxwcmVzcy1jc3JmLQ";

    private Csrf() {
    }

    public static RequestSpecification csrf() {
        return RestAssured.given().cookie("csrf-token", TOKEN).header("X-CSRF-TOKEN", TOKEN);
    }
}
