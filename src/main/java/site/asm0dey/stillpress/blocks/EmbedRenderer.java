package site.asm0dey.stillpress.blocks;

import jakarta.enterprise.context.ApplicationScoped;
import module java.base;

@ApplicationScoped
public class EmbedRenderer implements DynamicBlockRenderer {
    private static final Pattern YOUTUBE = Pattern.compile(
            "(?:youtube(?:-nocookie)?\\.com/(?:watch\\?(?:[^#]*&)?v=|shorts/|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})"
    );

    @Override
    public String blockName() {
        return "core/embed";
    }

    @Override
    public String render(Block block, String renderedInner, RenderContext ctx) {
        var url = block.attrs().path("url").asText("").trim();
        if (url.isEmpty()) {
            return renderedInner;
        }
        var m = YOUTUBE.matcher(url);
        if (m.find()) {
            var id = m.group(1);
            return 
                    "<figure class=\"wp-block-embed is-type-video is-provider-youtube wp-block-embed-youtube\"><div class=\"wp-block-embed__wrapper\">"
                    + "<button type=\"button\" class=\"sp-yt-facade\" data-yt-id=\""
                    + id
                    + "\" style=\"background-image:url(https://i.ytimg.com/vi/"
                    + id
                    + "/hqdefault.jpg)\" aria-label=\"Play video\"></button></div></figure>";
        }
        var u = esc(url);
        return "<figure class=\"wp-block-embed\"><a href=\"" + u + "\">" + u + "</a></figure>";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
