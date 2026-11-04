package site.asm0dey.stillpress.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import module java.base;
import site.asm0dey.stillpress.importer.ImportGuard;
import site.asm0dey.stillpress.serve.StaticSiteRoute;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.RedirectRepository;

/**
 * The administrator's redirect map. Stillpress serves redirects itself in both modes, so a change needs no publish.
 */
@Path("/admin/api/redirects")
@RolesAllowed("administrator")
public class RedirectsResource {
    private static final int PER_PAGE = 50;
    @Inject
    RedirectRepository redirects;
    @Inject
    ItemRepository items;
    @Inject
    ImportGuard guard;

    public record RedirectList(List<RedirectRepository.Row> items, int total) {}

    public record NewRedirect(String from, String to, Integer status) {}

    @GET
    public RedirectList list(@QueryParam("search") String search, @QueryParam("page") @DefaultValue("1") int page) {
        return new RedirectList(
                redirects.list(search, (Math.max(page, 1) - 1) * PER_PAGE, PER_PAGE),
                redirects.count(search)
        );
    }

    @POST
    @Transactional
    public Response add(NewRedirect n) {
        var field = invalidField(n);
        if (field != null) {
            return Response.status(400).entity(Map.of("error", "invalid", "field", field)).build();
        }
        if (items
            .findByPermalink(n.from())
            .filter(i -> "publish".equals(i.getStatus()))
            .isPresent()) {
            return Response.status(409).entity(Map.of("error", "shadows_item", "field", "from")).build();
        }
        guard.close();
        redirects.add(n.from(), n.to(), n.status());
        return Response.status(201).entity(new RedirectRepository.Row(n.from(), n.to(), n.status())).build();
    }

    @DELETE
    @Transactional
    public Response delete(@QueryParam("from") String from) {
        if (from == null || redirects.find(from).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        guard.close();
        redirects.delete(from);
        return Response.noContent().build();
    }

    private static String invalidField(NewRedirect n) {
        if (n == null || !validFrom(n.from())) {
            return "from";
        }
        if (!validTo(n.to()) || n.to().equals(n.from())) {
            return "to";
        }
        return n.status() == null || n.status() != 301 && n.status() != 302 ? "status" : null;
    }

    /**
     * A decoded path, or a query such as the importer's {@code ?p=N}; no {@code ..} segment, no control characters.
     */
    static boolean validFrom(String v) {
        return v != null
                && v.length() <= 2000
                && (v.startsWith("/") || v.startsWith("?") && v.length() > 1)
                && noControl(v)
                && Arrays.stream(v.split("[/?]")).noneMatch(".."::equals);
    }

    /**
     * A site path ({@code /...}, not {@code //...}) or an absolute http(s) URL.
     */
    static boolean validTo(String v) {
        if (v == null || v.length() > 2000 || !noControl(v) || v.indexOf('\\') >= 0) {
            return false;
        }
        if (v.startsWith("/")) {
            return !v.startsWith("//") && Arrays.stream(v.split("/")).noneMatch(".."::equals);
        }
        return StaticSiteRoute.absoluteUrl(v);
    }

    private static boolean noControl(String v) {
        return v.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
    }
}
