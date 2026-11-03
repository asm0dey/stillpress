package site.asm0dey.stillpress.blocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of WordPress's WP_Block_Parser. Never throws on malformed input. */
public final class BlockParser {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  // [^}]++ is possessive to avoid StackOverflowError on long attribute JSON.
  private static final Pattern DELIM = Pattern.compile(
      "<!--\\s+(?<closer>/)?wp:(?<namespace>[a-z][a-z0-9_-]*/)?(?<name>[a-z][a-z0-9_-]*)\\s+"
          + "(?<attrs>\\{(?:(?:[^}]++|}+(?=})|(?!}\\s+/?-->).)*)?}\\s+)?(?<void>/)?-->",
      Pattern.DOTALL);

  private record Frame(Block block, int tokenStart, int tokenLength, int leadingStart, int[] prevOffset) {}

  private final String doc;
  private final List<Block> output = new ArrayList<>();
  private final Deque<Frame> stack = new ArrayDeque<>();
  private int offset;

  private BlockParser(String doc) { this.doc = doc; }

  public static List<Block> parse(String document) {
    var p = new BlockParser(document == null ? "" : document);
    var m = DELIM.matcher(p.doc);
    try {
      while (p.step(m)) { /* until done */ }
    } catch (StackOverflowError e) {
      p.stack.clear();
      p.addFreeform();
    }
    return p.output;
  }

  private boolean step(Matcher m) {
    if (!m.find(offset)) {
      if (stack.isEmpty()) addFreeform();
      else while (!stack.isEmpty()) addBlockFromStack(-1); // implicit closers
      return false;
    }
    int start = m.start(), length = m.end() - start;
    var block = newBlock(m);
    if (m.group("closer") != null) {
      if (stack.isEmpty()) { addFreeform(); return false; }
      if (stack.size() == 1) {
        addBlockFromStack(start);
      } else {
        var top = stack.pop();
        appendHtml(top.block, doc.substring(top.prevOffset[0], start));
        addInnerBlock(top.block, top.tokenStart, top.tokenLength, start + length);
      }
    } else if (m.group("void") != null) {
      if (stack.isEmpty()) {
        if (start > offset) output.add(freeform(doc.substring(offset, start)));
        output.add(block);
      } else {
        addInnerBlock(block, start, length, -1);
      }
    } else {
      int leading = stack.isEmpty() && start > offset ? offset : -1;
      stack.push(new Frame(block, start, length, leading, new int[] {start + length}));
    }
    offset = start + length;
    return true;
  }

  private static Block newBlock(Matcher m) {
    String ns = m.group("namespace");
    var name = (ns == null ? "core/" : ns) + m.group("name");
    JsonNode attrs = null;
    if (m.group("attrs") != null) {
      try { attrs = MAPPER.readTree(m.group("attrs").trim()); } catch (Exception e) { /* invalid JSON -> empty */ }
    }
    if (attrs == null || !attrs.isObject()) attrs = MAPPER.createObjectNode();
    return new Block(name, attrs, new ArrayList<>(), new ArrayList<>());
  }

  private static Block freeform(String html) {
    return new Block(null, MAPPER.createObjectNode(), new ArrayList<>(), new ArrayList<>(List.of(html)));
  }

  private static void appendHtml(Block b, String html) {
    if (!html.isEmpty()) b.innerContent().add(html);
  }

  private void addFreeform() {
    if (offset < doc.length()) output.add(freeform(doc.substring(offset)));
  }

  private void addInnerBlock(Block block, int tokenStart, int tokenLength, int lastOffset) {
    var parent = stack.peek();
    parent.block.innerBlocks().add(block);
    appendHtml(parent.block, doc.substring(parent.prevOffset[0], tokenStart));
    parent.block.innerContent().add(null);
    parent.prevOffset[0] = lastOffset >= 0 ? lastOffset : tokenStart + tokenLength;
  }

  private void addBlockFromStack(int end) {
    var top = stack.pop();
    appendHtml(top.block, doc.substring(top.prevOffset[0], end >= 0 ? end : doc.length()));
    if (top.leadingStart >= 0) output.add(freeform(doc.substring(top.leadingStart, top.tokenStart)));
    output.add(top.block);
  }
}
