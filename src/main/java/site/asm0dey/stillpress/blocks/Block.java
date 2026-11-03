package site.asm0dey.stillpress.blocks;

import com.fasterxml.jackson.databind.JsonNode;
import module java.base;

/** Parsed WordPress block. {@code name == null} is freeform HTML; {@code innerContent} holds one {@code null} per inner block. */
public record Block(String name, JsonNode attrs, List<Block> innerBlocks, List<String> innerContent) {
  public String innerHtml() {
    var sb = new StringBuilder();
    for (var s : innerContent) if (s != null) sb.append(s);
    return sb.toString();
  }
}
