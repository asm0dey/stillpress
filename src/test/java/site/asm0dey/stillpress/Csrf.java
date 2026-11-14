package site.asm0dey.stillpress;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

/**
 * Test helper: a request carrying the CSRF token the way the admin UI sends it. A GET issues the raw token in the
 * {@code X-CSRF-TOKEN} response header and its signature in the {@code csrf-token} cookie; a write sends both back.
 */
public final class Csrf {
    private Csrf() {
    }

    public record Token(String cookie, String header) {}

    public static Token issue() {
        var r = RestAssured.given().get("/admin/api/setup").then().statusCode(200).extract();
        return new Token(r.cookie("csrf-token"), r.header("X-CSRF-TOKEN"));
    }

    public static RequestSpecification csrf() {
        var token = issue();
        return RestAssured.given().cookie("csrf-token", token.cookie()).header("X-CSRF-TOKEN", token.header());
    }
}
