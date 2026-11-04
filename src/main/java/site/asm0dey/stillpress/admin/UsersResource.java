package site.asm0dey.stillpress.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import module java.base;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.account.SetPasswordLink;
import site.asm0dey.stillpress.db.tables.records.AuthorRecord;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.store.AuthorRepository;

/**
 * Administrator-only user management; set-password links are handed over by the administrator until mail exists.
 */
@Path("/admin/api/users")
@RolesAllowed("administrator")
public class UsersResource {
    @Inject
    AuthorRepository users;
    @Inject
    SetPasswordLink links;
    @Inject
    ImportGuard guard;

    public record UserView(long id, String login, String displayName, String email, String role, boolean hasPassword) {}

    public record NewUser(String login, String email, String displayName, String role) {}

    public record RoleChange(String role) {}

    @GET
    public List<UserView> list() {
        return users
            .all()
            .stream()
            .sorted(Comparator.comparing(AuthorRecord::getLogin))
            .map(u -> new UserView(
                    u.getId(),
                    u.getLogin(),
                    u.getDisplayName(),
                    u.getEmail(),
                    u.getRole(),
                    u.getPasswordHash() != null
            ))
            .toList();
    }

    @POST
    @Transactional
    public Response create(NewUser n) {
        if (n == null
                || blank(n.login())
                || blank(n.email())
                || n.email().indexOf('@') < 1
                || blank(n.displayName())) {
            return error(400, "invalid");
        }
        var role = role(n.role());
        if (role == null) {
            return error(400, "invalid_role");
        }
        if (users
            .findByLoginOrEmail(n.login())
            .filter(u -> u.getLogin().equals(n.login()))
            .isPresent()) {
            return error(409, "login_taken");
        }
        guard.close();
        var id = users.create(n.login(), n.email(), n.displayName(), role).getId();
        return Response.status(201).entity(Map.of("id", id, "setPasswordUrl", links.issue(id))).build();
    }

    @PUT
    @Path("{id}/role")
    @Transactional
    public Response setRole(@PathParam("id") long id, RoleChange c) {
        var role = c == null ? null : role(c.role());
        if (role == null) {
            return error(400, "invalid_role");
        }
        if (users.findById(id).isEmpty()) {
            return error(404, "not_found");
        }
        guard.close();
        return users.setRoleKeepingAdministrator(id, role)
                ? Response.noContent().build()
                : error(409, "last_administrator");
    }

    @POST
    @Path("{id}/password-link")
    @Transactional
    public Response passwordLink(@PathParam("id") long id) {
        if (users.findById(id).isEmpty()) {
            return error(404, "not_found");
        }
        guard.close();
        return Response.ok(Map.of("url", links.issue(id))).build();
    }

    private static Role role(String id) {
        try {
            return Role.of(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static Response error(int status, String code) {
        return Response.status(status).entity(Map.of("error", code)).build();
    }
}
