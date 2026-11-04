package site.asm0dey.stillpress.content;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import module java.base;
import site.asm0dey.stillpress.account.Role;
import site.asm0dey.stillpress.publish.views.ViewFactory;
import site.asm0dey.stillpress.store.ItemRepository;
import site.asm0dey.stillpress.store.ReusableBlockRepository;

/**
 * {@link HtmlPolicy} applied to a save by a role without {@link Capabilities#canPostUnfilteredHtml}: the source, its
 * block structure, and the HTML as the publish pipeline renders it ({@link ViewFactory#html}), so what is judged is
 * what gets published. A finding is a 400 {@code unsafe_html}; {@code field} names the first offending part.
 */
@ApplicationScoped
public class HtmlCheck {
    @Inject
    ViewFactory views;
    @Inject
    ReusableBlockRepository blocks;
    @Inject
    ItemRepository items;

    void item(Role role, String title, String body, String bodyFormat, String excerpt) {
        if (Capabilities.canPostUnfilteredHtml(role)) {
            return;
        }
        var found = new LinkedHashMap<String, List<String>>();
        found.put("title", HtmlPolicy.violations(title));
        found.put("body", published(body, bodyFormat, blocks::content));
        found.put("excerpt", published(excerpt, "classic", blocks::content));
        refuse(found);
    }

    /**
     * A reusable block's save: its own content, and every item that shows it ({@code uses}) rendered with the new
     * content, whoever wrote the item.
     */
    void block(Role role, Long ref, String title, String content, Supplier<List<ReusableBlockRepository.Use>> uses) {
        if (Capabilities.canPostUnfilteredHtml(role)) {
            return;
        }
        Function<Long, Optional<String>> lookup =
                id -> ref != null && id.equals(ref) && content != null ? Optional.of(content) : blocks.content(id);
        var inItems = new ArrayList<String>();
        if (content != null) {
            for (var u : uses.get()) {
                items
                    .find(u.itemId())
                    .ifPresent(i -> published(i.getBody(), i.getBodyFormat(), lookup)
                        .forEach(v -> inItems.add("in \"" + Objects.requireNonNullElse(i.getTitle(), "") + "\": " + v)));
            }
        }
        var found = new LinkedHashMap<String, List<String>>();
        found.put("title", HtmlPolicy.violations(title));
        var body = new ArrayList<>(published(content, "blocks", lookup));
        body.addAll(inItems);
        found.put("body", body);
        refuse(found);
    }

    private List<String> published(String source, String format, Function<Long, Optional<String>> lookup) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        var out = new LinkedHashSet<>(HtmlPolicy.violations(source));
        out.addAll(HtmlPolicy.structure(source));
        out.addAll(HtmlPolicy.violations(views.html(source, format, lookup)));
        return List.copyOf(out);
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
