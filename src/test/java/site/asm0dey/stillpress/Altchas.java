package site.asm0dey.stillpress;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import module java.base;
import org.altcha.altcha.v1.Altcha;

/**
 * Test helper: solves an ALTCHA challenge the way the widget does, and the port the {@code %test} Turnstile verify-url
 * points at.
 */
public final class Altchas {
    public static final int TURNSTILE_PORT = 8093;

    private Altchas() {
    }

    /**
     * A challenge from the public endpoint, solved.
     */
    public static String solved() {
        var c = RestAssured
            .given()
            .get("/admin/api/altcha/challenge")
            .then()
            .statusCode(200)
            .extract()
            .as(Altcha.Challenge.class);
        return solve(c);
    }

    /**
     * The widget's payload: base64 of the JSON {@code {algorithm, challenge, number, salt, signature}}.
     */
    public static String solve(Altcha.Challenge c) {
        try {
            var solution = Altcha.solveChallenge(c.challenge(), c.salt(), Altcha.Algorithm.SHA256, c.maxnumber(), 0);
            var json = new ObjectMapper()
                .writeValueAsString(Map.of(
                        "algorithm",
                        c.algorithm(),
                        "challenge",
                        c.challenge(),
                        "number",
                        solution.number(),
                        "salt",
                        c.salt(),
                        "signature",
                        c.signature()
                ));
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
