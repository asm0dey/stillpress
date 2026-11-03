package site.asm0dey.stillpress.blocks;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import module java.base;

@ApplicationScoped
public class BlockRenderer {
  static final int MAX_DEPTH = 5;

  @Inject Instance<DynamicBlockRenderer> dynamic;
  private volatile Map<String, DynamicBlockRenderer> byName;

  public String render(String document, RenderContext ctx) {
    return render(BlockParser.parse(document), ctx);
  }

  public String render(List<Block> blocks, RenderContext ctx) {
    if (ctx.depth() > MAX_DEPTH) return "";
    var sb = new StringBuilder();
    for (var b : blocks) sb.append(renderBlock(b, ctx));
    return sb.toString();
  }

  private String renderBlock(Block b, RenderContext ctx) {
    if (b.name() == null) return b.innerHtml();
    var sb = new StringBuilder();
    int next = 0;
    for (var part : b.innerContent()) {
      if (part != null) sb.append(part);
      else if (next < b.innerBlocks().size()) sb.append(renderBlock(b.innerBlocks().get(next++), ctx));
    }
    var r = renderers().get(b.name());
    return r == null ? sb.toString() : r.render(b, sb.toString(), ctx);
  }

  // Lazy: ReusableBlockRenderer injects this bean, so resolving renderers at init would be circular.
  private Map<String, DynamicBlockRenderer> renderers() {
    var m = byName;
    if (m == null) {
      m = new HashMap<>();
      for (var r : dynamic) m.put(r.blockName(), r);
      byName = m;
    }
    return m;
  }
}
