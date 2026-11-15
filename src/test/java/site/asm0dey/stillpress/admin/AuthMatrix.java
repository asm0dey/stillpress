package site.asm0dey.stillpress.admin;

import static io.restassured.http.ContentType.JSON;
import static site.asm0dey.stillpress.Csrf.csrf;
import io.restassured.RestAssured;
import io.restassured.response.Response;

/**
 * The HTTP-level authorization checks every resource keeps once its logic is tested without Quarkus: the request
 * reaches (or is stopped before) the resource the way the container wires it.
 */
final class AuthMatrix {
    private AuthMatrix() {
    }

    /**
     * A write carrying a valid CSRF token and, when {@code session} is not null, that session's cookie.
     */
    static Response send(String method, String path, String session, String body) {
        var spec = csrf().contentType(JSON);
        if (session != null) {
            spec = spec.cookie("sp_session", session);
        }
        return body == null ? spec.request(method, path) : spec.body(body).request(method, path);
    }

    static void anonymousIs401(String method, String path) {
        send(method, path, null, "{}").then().statusCode(401);
    }

    static void forbiddenFor(String session, String method, String path) {
        forbiddenFor(session, method, path, "{}");
    }

    static void forbiddenFor(String session, String method, String path, String body) {
        send(method, path, session, body).then().statusCode(403);
    }

    /**
     * A write with a session but without the CSRF token is refused before the resource runs.
     */
    static void missingCsrfIs400(String session, String method, String path) {
        RestAssured
            .given()
            .contentType(JSON)
            .cookie("sp_session", session)
            .body("{}")
            .request(method, path)
            .then()
            .statusCode(400);
    }
}
