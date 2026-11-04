package site.asm0dey.stillpress.content;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.blocks.BlockRenderer;
import site.asm0dey.stillpress.publish.views.ViewFactory;
import site.asm0dey.stillpress.store.AuthorRepository;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.ReusableBlockRepository;

/**
 * {@link HtmlPolicy} applied to a save by a role without {@link Capabilities#canPostUnfilteredHtml} (R27). Every
 * fragment such a role writes (item body and excerpt, reusable block content) must pass {@link HtmlPolicy#fragment}
 * on its own, so fragments side by side on a page stay safe; item bodies are also checked as the publish pipeline
 * renders them ({@link ViewFactory#html}), reusable blocks resolved. Callers pass the state as stored after the save.
 * A finding is a 400 {@code unsafe_html}; {@code field} names the first offending part.
 */
@ApplicationScoped
public class HtmlCheck {
    private static final String EDITORS_BLOCK = " in a reusable block owned by an editor; an editor must change it";
    private static final String EDITORS_ITEM = " in an item owned by an editor; an editor must change it";
    @Inject
    ViewFactory views;
    @Inject
    ReusableBlockRepository blocks;
    @Inject
    ItemRepository items;
    @Inject
    AuthorRepository authors;

    void item(Role role, String title, String body, String bodyFormat, String excerpt) {
        if (Capabilities.canPostUnfilteredHtml(role)) {
            return;
        }
        var found = new LinkedHashMap<String, List<String>>();
        found.put("title", HtmlPolicy.violations(title));
        found.put("body", body(body, bodyFormat, blocks::content));
        found.put("excerpt", HtmlPolicy.fragment(excerpt));
        refuse(found);
    }

    /**
     * A reusable block's save: its own content, then every item that shows it ({@code uses}, trashed ones too)
     * rendered with the new content, whoever wrote the item.
     */
    void block(Role role, Long ref, String title, String content, Supplier<List<ReusableBlockRepository.Use>> uses) {
        if (Capabilities.canPostUnfilteredHtml(role)) {
            return;
        }
        Function<Long, Optional<String>> lookup =
                id -> ref != null && id.equals(ref) && content != null ? Optional.of(content) : blocks.content(id);
        var body = new ArrayList<>(body(content, "blocks", lookup));
        if (content != null && body.isEmpty()) {
            for (var u : uses.get()) {
                items.find(u.itemId()).ifPresent(i -> {
                    var where = "in \"" + Objects.requireNonNullElse(i.getTitle(), "") + "\": ";
                    var owner = editorOwned(i.getAuthorId()) ? EDITORS_ITEM : "";
                    rendered(i.getBody(), i.getBodyFormat(), lookup).forEach(v -> body.add(where + v + owner));
                });
            }
        }
        var found = new LinkedHashMap<String, List<String>>();
        found.put("title", HtmlPolicy.violations(title));
        found.put("body", body);
        refuse(found);
    }

    /**
     * The fragment checks, and when they pass, the rendered body. A rendered finding then comes from the reusable
     * blocks shown; when all faulty ones belong to editors, the message says so.
     */
    private List<String> body(String source, String format, Function<Long, Optional<String>> lookup) {
        var own = HtmlPolicy.fragment(source);
        if (!own.isEmpty() || source == null || source.isBlank()) {
            return own;
        }
        var rendered = rendered(source, format, lookup);
        if (rendered.isEmpty()) {
            return rendered;
        }
        var faulty = faultyBlocks(source, lookup);
        var editors = !faulty.isEmpty() && faulty.stream().allMatch(this::editorOwnedBlock);
        return editors ? rendered.stream().map(v -> v + EDITORS_BLOCK).toList() : rendered;
    }

    private List<String> rendered(String source, String format, Function<Long, Optional<String>> lookup) {
        return HtmlPolicy.violations(views.html(source, format, lookup));
    }

    /**
     * Reusable blocks shown by {@code source}, nested ones too, whose content fails the fragment checks.
     */
    private List<Long> faultyBlocks(String source, Function<Long, Optional<String>> lookup) {
        var seen = new LinkedHashSet<Long>();
        var frontier = ReusableBlockRepository.refsIn(source);
        for (int depth = 0; depth <= BlockRenderer.MAX_DEPTH && !frontier.isEmpty(); depth++) {
            var next = new HashSet<Long>();
            for (var ref : frontier) {
                if (seen.add(ref)) {
                    lookup
                        .apply(ref)
                        .ifPresent(c -> next.addAll(ReusableBlockRepository.refsIn(c)));
                }
            }
            frontier = next;
        }
        return seen
            .stream()
            .filter(r -> lookup
                .apply(r)
                .map(c -> !HtmlPolicy.fragment(c).isEmpty())
                .orElse(false))
            .toList();
    }

    private boolean editorOwnedBlock(long ref) {
        return blocks
            .find(ref)
            .map(b -> editorOwned(b.getAuthorId()))
            .orElse(false);
    }

    private boolean editorOwned(Long authorId) {
        return Optional
            .ofNullable(authorId)
            .flatMap(authors::findById)
            .map(a -> Capabilities.canPostUnfilteredHtml(Role.of(a.getRole())))
            .orElse(false);
    }

    private static void refuse(Map<String, List<String>> found) {
        var field = found
            .entrySet()
            .stream()
            .filter(e -> !e.getValue().isEmpty())
            .map(Map.Entry::getKey)
            .findFirst();
        if (field.isPresent()) {
            var all = found.values().stream().flatMap(List::stream).distinct().toList();
            throw new ItemService.Refused(400, "unsafe_html", field.get(), all);
        }
    }
}
