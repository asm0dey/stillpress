package site.asm0dey.stillpress.blocks;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ReusableBlockRenderer implements DynamicBlockRenderer {
  @Inject BlockRenderer renderer;

  @Override public String blockName() { return "core/block"; }

  @Override public String render(Block block, String renderedInner, RenderContext ctx) {
    var ref = block.attrs().path("ref");
    if (!ref.canConvertToLong()) return "";
    return ctx.reusableBlockContent().apply(ref.asLong())
      .map(doc -> renderer.render(doc, ctx.nested())).orElse("");
  }
}
