package site.asm0dey.stillpress.admin;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import module java.base;
import org.jooq.exception.DataAccessException;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.account.SetPasswordLink;
import site.asm0dey.stillpress.account.UserFields;
import site.asm0dey.stillpress.db.tables.records.AuthorRecord;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.publish.Jobs;
import site.asm0dey.stillpress.publish.PublishQueue;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.FragmentRepository;
import site.asm0dey.stillpress.store.ItemRepository;

/**
 * Administrator-only user management, except that anyone edits their own profile; set-password links are handed over by the administrator until mail exists.
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
    @Inject
    ItemRepository items;
    @Inject
    FragmentRepository fragments;
    @Inject
    PublishQueue queue;
    @Inject
    Jobs jobs;
    @Inject
    SecurityIdentity identity;

    public record UserView(long id, String login, String displayName, String email, String role, boolean hasPassword) {}

    public record NewUser(String login, String email, String displayName, String role) {}

    public record RoleChange(String role) {}

    public record Profile(String displayName, String email) {}

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
        var login = UserFields.trim(n == null ? null : n.login());
        var email = UserFields.trim(n == null ? null : n.email());
        var name = UserFields.trim(n == null ? null : n.displayName());
        if (!UserFields.validLogin(login)) {
            return invalid("login");
        }
        if (!UserFields.validEmail(email)) {
            return invalid("email");
        }
        if (!UserFields.validDisplayName(name)) {
            return invalid("displayName");
        }
        var role = role(n.role());
        if (role == null) {
            return error(400, "invalid_role");
        }
        if (users.findByLogin(login).isPresent()) {
            return error(409, "login_taken");
        }
        if (users.emailTaken(email, -1)) {
            return error(409, "email_taken");
        }
        guard.close();
        long id;
        try {
            id = users.create(login, email, name, role).getId();
        } catch (DataAccessException e) {
            if (!"23505".equals(e.sqlState())) {
                throw e;
            }
            // a concurrent create took the login; thrown, so the transaction rolls back
            throw new WebApplicationException(error(409, "login_taken"));
        }
        return Response.status(201).entity(Map.of("id", id, "setPasswordUrl", links.issue(id))).build();
    }

    /**
     * Display name and email. The author slug, and so the {@code /author/<slug>/} address, never changes; a new name
     * republishes the author archive and every published item by the user, which show it.
     */
    @PUT
    @Path("{id}")
    @Authenticated
    @Transactional
    public Response update(@PathParam("id") long id, Profile p) {
        var a = ItemsResource.actor(identity);
        if (a.role() != Role.ADMINISTRATOR && a.userId() != id) {
            return error(403, "forbidden");
        }
        var u = users.findById(id).orElse(null);
        if (u == null) {
            return error(404, "not_found");
        }
        var name = UserFields.trim(p == null ? null : p.displayName());
        var email = UserFields.trim(p == null ? null : p.email());
        if (!UserFields.validDisplayName(name)) {
            return invalid("displayName");
        }
        if (!UserFields.validEmail(email)) {
            return invalid("email");
        }
        if (users.emailTaken(email, id)) {
            return error(409, "email_taken");
        }
        guard.close();
        users.setProfile(id, name, email);
        if (!name.equals(u.getDisplayName())) {
            var written = items.publishedIdsBy(id);
            fragments.delete(written);
            queue.items(written);
            queue.base("/author/" + u.getSlug() + "/");
            jobs.requestPublish();
        }
        return Response.noContent().build();
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
        if (!users.setRoleKeepingAdministrator(id, role)) {
            return error(409, "last_administrator");
        }
        guard.close();
        return Response.noContent().build();
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

    private static Response invalid(String field) {
        return Response.status(400).entity(Map.of("error", "invalid", "field", field)).build();
    }

    private static Response error(int status, String code) {
        return Response.status(status).entity(Map.of("error", code)).build();
    }
}
