package site.asm0dey.stillpress.blocks;

/** CDI bean rendering one dynamic block type; collected by {@link BlockRenderer}. */
public interface DynamicBlockRenderer {
  String blockName();
  String render(Block block, String renderedInner, RenderContext ctx);
}
