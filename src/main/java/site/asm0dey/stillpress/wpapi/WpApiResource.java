package site.asm0dey.stillpress.wpapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import module java.base;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import site.asm0dey.stillpress.admin.ItemsResource;
import site.asm0dey.stillpress.content.Capabilities;
import site.asm0dey.stillpress.content.ItemService;
import site.asm0dey.stillpress.content.ReusableBlockService;
import site.asm0dey.stillpress.db.tables.records.MediaRecord;
import site.asm0dey.stillpress.db.tables.records.ReusableBlockRecord;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.MediaRepository;
import site.asm0dey.stillpress.store.ReusableBlockRepository;

/**
 * The slice of WordPress's REST API that the embedded block library calls, in WordPress's JSON shapes. Anything else
 * is logged and answered 404 {@code rest_no_route}, so a missing endpoint shows up in the log.
 */
@Path("/wp-json")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class WpApiResource {
    private static final Logger LOG = Logger.getLogger(WpApiResource.class);
    private static final String ALL = "GET, POST, PUT, PATCH, DELETE";
    private static final Map<String, Map<String, Object>> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("post", type("post", "Posts", "Post", "posts", true, false, List.of("category", "post_tag")));
        TYPES.put("page", type("page", "Pages", "Page", "pages", true, true, List.of()));
        TYPES.put("wp_block", type("wp_block", "Patterns", "Pattern", "blocks", false, false, List.of()));
        TYPES.put("attachment", type("attachment", "Media", "Media", "media", true, false, List.of()));
    }

    @Inject
    ReusableBlockService service;
    @Inject
    ReusableBlockRepository blocks;
    @Inject
    MediaRepository media;
    @Inject
    AuthorRepository authors;
    @Inject
    OEmbedProxy oembed;
    @Inject
    SecurityIdentity identity;

    @ServerExceptionMapper
    public Response refused(ItemService.Refused e) {
        return error(e.status, e.error);
    }

    @GET
    @Path("wp/v2/blocks")
    public List<Map<String, Object>> blocks() {
        return blocks.all().stream().map(WpApiResource::block).toList();
    }

    @GET
    @Path("wp/v2/blocks/{id: \\d+}")
    public Map<String, Object> block(@PathParam("id") long id) {
        return block(find(id));
    }

    @POST
    @Path("wp/v2/blocks")
    public Response create(JsonNode body) {
        var b = service.save(null, text(body, "title"), text(body, "content"), null, actor());
        return Response.status(201).entity(block(b)).build();
    }

    @POST
    @Path("wp/v2/blocks/{id: \\d+}")
    public Map<String, Object> post(@PathParam("id") long id, JsonNode body) {
        return update(id, body);
    }

    @PUT
    @Path("wp/v2/blocks/{id: \\d+}")
    public Map<String, Object> update(@PathParam("id") long id, JsonNode body) {
        return block(service.overwrite(id, text(body, "title"), text(body, "content"), actor()));
    }

    @PATCH
    @Path("wp/v2/blocks/{id: \\d+}")
    public Map<String, Object> patch(@PathParam("id") long id, JsonNode body) {
        return update(id, body);
    }

    /**
     * core-data's {@code canUser}: the methods this user may call.
     */
    @OPTIONS
    @Path("wp/v2/blocks")
    public Response blocksAllow() {
        return allow(Capabilities.canCreateBlock(actor().role()));
    }

    @OPTIONS
    @Path("wp/v2/blocks/{id: \\d+}")
    public Response blockAllow(@PathParam("id") long id) {
        var a = actor();
        return allow(Capabilities.canEditBlock(a.role(), a.userId(), find(id)));
    }

    @GET
    @Path("wp/v2/types")
    public Map<String, Map<String, Object>> types() {
        return TYPES;
    }

    @GET
    @Path("wp/v2/types/{slug}")
    public Map<String, Object> type(@PathParam("slug") String slug) {
        return Optional
            .ofNullable(TYPES.get(slug))
            .orElseThrow(() -> new ItemService.Refused(404, "rest_type_invalid", null));
    }

    @GET
    @Path("wp/v2/themes")
    public List<Map<String, Object>> themes() {
        return List.of(Map.of(
                "stylesheet",
                "plain",
                "template",
                "plain",
                "status",
                "active",
                "name",
                Map.of("raw", "Plain", "rendered", "Plain"),
                "theme_supports",
                Map.of("align-wide", true, "responsive-embeds", true, "editor-styles", true)
        ));
    }

    @GET
    @Path("wp/v2/media/{id: \\d+}")
    public Map<String, Object> media(@PathParam("id") long id) {
        return media
            .findById(id)
            .map(WpApiResource::media)
            .orElseThrow(() -> new ItemService.Refused(404, "rest_post_invalid_id", null));
    }

    /**
     * Only {@code include} lookups ({@code include=1,2} or apiFetch's {@code include[0]=1}); the library listing comes
     * with the media screen.
     */
    @GET
    @Path("wp/v2/media")
    public List<Map<String, Object>> mediaList(@Context UriInfo uri) {
        var ids = uri
            .getQueryParameters()
            .entrySet()
            .stream()
            .filter(e -> e.getKey().equals("include") || e.getKey().startsWith("include["))
            .flatMap(e -> e.getValue().stream())
            .flatMap(v -> Arrays.stream(v.split(",")))
            .map(String::strip)
            .filter(v -> v.matches("\\d{1,18}"))
            .map(Long::parseLong)
            .limit(100)
            .toList();
        return ids.isEmpty() ? List.of() : media.findByIds(ids).stream().map(WpApiResource::media).toList();
    }

    @GET
    @Path("wp/v2/users/me")
    public Map<String, Object> me() {
        var u = authors.findById(actor().userId()).orElseThrow();
        return Map.of(
                "id",
                u.getId(),
                "name",
                Objects.requireNonNullElse(u.getDisplayName(), u.getLogin()),
                "slug",
                u.getSlug()
        );
    }

    @GET
    @Path("oembed/1.0/proxy")
    public Response oembed(@QueryParam("url") String url) {
        return oembed.fetch(url);
    }

    @GET
    @Path("{any: .*}")
    public Response unknownGet(@Context UriInfo uri, @Context Request req) {
        return unknown(uri, req);
    }

    @POST
    @Path("{any: .*}")
    public Response unknownPost(@Context UriInfo uri, @Context Request req) {
        return unknown(uri, req);
    }

    @PUT
    @Path("{any: .*}")
    public Response unknownPut(@Context UriInfo uri, @Context Request req) {
        return unknown(uri, req);
    }

    @PATCH
    @Path("{any: .*}")
    public Response unknownPatch(@Context UriInfo uri, @Context Request req) {
        return unknown(uri, req);
    }

    @DELETE
    @Path("{any: .*}")
    public Response unknownDelete(@Context UriInfo uri, @Context Request req) {
        return unknown(uri, req);
    }

    @OPTIONS
    @Path("{any: .*}")
    public Response unknownOptions(@Context UriInfo uri, @Context Request req) {
        return unknown(uri, req);
    }

    private static Response unknown(UriInfo uri, Request req) {
        var u = uri.getRequestUri();
        LOG.warn(
                "Unknown /wp-json call: "
                + req.getMethod()
                + " "
                + u.getRawPath()
                + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery())
        );
        return error(404, "rest_no_route");
    }

    private ReusableBlockRecord find(long id) {
        return blocks
            .find(id)
            .orElseThrow(() -> new ItemService.Refused(404, "rest_post_invalid_id", null));
    }

    private ItemService.Actor actor() {
        return ItemsResource.actor(identity);
    }

    private static Response allow(boolean write) {
        return Response.ok().header("Allow", write ? ALL : "GET").build();
    }

    private static Response error(int status, String code) {
        return Response
            .status(status)
            .type(MediaType.APPLICATION_JSON_TYPE)
            .entity(Map.of("code", code, "message", code, "data", Map.of("status", status)))
            .build();
    }

    /**
     * A field WordPress accepts as a string or as {@code {"raw": ...}}; null when absent.
     */
    private static String text(JsonNode body, String field) {
        var n = body == null ? null : body.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isObject()) {
            return n.hasNonNull("raw") ? n.get("raw").asText() : null;
        }
        return n.asText();
    }

    private static Map<String, Object> block(ReusableBlockRecord b) {
        var title = Objects.requireNonNullElse(b.getTitle(), "");
        var m = new LinkedHashMap<String, Object>();
        m.put("id", b.getSourceId());
        m.put("type", "wp_block");
        m.put("status", "publish");
        m.put("wp_pattern_sync_status", "");
        m.put("title", Map.of("raw", title, "rendered", title));
        m.put("content", Map.of("raw", b.getContent(), "protected", false));
        m.put("wp_pattern_category", List.of());
        return m;
    }

    private static Map<String, Object> media(MediaRecord r) {
        var mime = Objects.requireNonNullElse(r.getMimeType(), "application/octet-stream");
        var name = r.getPath() == null ? "" : r.getPath().substring(r.getPath().lastIndexOf('/') + 1);
        var details = new LinkedHashMap<String, Object>();
        details.put("width", r.getWidth());
        details.put("height", r.getHeight());
        details.put("sizes", Map.of());
        var m = new LinkedHashMap<String, Object>();
        m.put("id", r.getId());
        m.put("source_url", r.getPath());
        m.put("media_type", mime.startsWith("image/") ? "image" : "file");
        m.put("mime_type", mime);
        m.put("alt_text", Objects.requireNonNullElse(r.getAltText(), ""));
        m.put("title", Map.of("rendered", Objects.requireNonNullElse(r.getTitle(), name)));
        m.put("media_details", details);
        return m;
    }

    private static Map<String, Object> type(
            String slug,
            String name,
            String singular,
            String restBase,
            boolean viewable,
            boolean hierarchical,
            List<String> taxonomies
    ) {
        var m = new LinkedHashMap<String, Object>();
        m.put("slug", slug);
        m.put("name", name);
        m.put("labels", Map.of("name", name, "singular_name", singular));
        m.put("rest_base", restBase);
        m.put("rest_namespace", "wp/v2");
        m.put("viewable", viewable);
        m.put("hierarchical", hierarchical);
        m.put("taxonomies", taxonomies);
        m.put("supports", Map.of("title", true, "editor", !"attachment".equals(slug), "revisions", true));
        return m;
    }
}
