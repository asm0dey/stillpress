package site.asm0dey.stillpress.blocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import module java.base;

/**
 * Port of WordPress's WP_Block_Parser. Never throws on malformed input.
 */
public final class BlockParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // The attrs JSON is located by hand (first '}' followed by "\s+/?-->"), so no regex recursion grows with input size.
    private static final Pattern HEAD =
            Pattern.compile("<!--\\s+(?<closer>/)?wp:(?<namespace>[a-z][a-z0-9_-]*/)?(?<name>[a-z][a-z0-9_-]*)\\s+");
    private static final Pattern ATTRS_END = Pattern.compile("}\\s+(?<void>/)?-->");
    private static final Pattern TAIL = Pattern.compile("(?<void>/)?-->");

    private record Frame(Block block, int tokenStart, int tokenLength, int leadingStart, int[] prevOffset) {}

    private final String doc;
    private final List<Block> output = new ArrayList<>();
    private final Deque<Frame> stack = new ArrayDeque<>();
    private int offset;

    private BlockParser(String doc) {
        this.doc = doc;
    }

    public static List<Block> parse(String document) {
        var p = new BlockParser(document == null ? "" : document);
        while (p.step()) {
            /* until done */
        }
        return p.output;
    }

    /**
     * Delimiter found at {@code start..end}.
     */
    private record Token(int start, int end, boolean closer, boolean isVoid, String name, String attrs) {}

    private Token nextToken() {
        var head = HEAD.matcher(doc);
        var attrsEnd = ATTRS_END.matcher(doc);
        var tail = TAIL.matcher(doc);
        for (int from = offset; head.find(from); from = head.start() + 1) {
            int p = head.end();
            String attrs = null;
            Matcher end;
            if (p < doc.length() && doc.charAt(p) == '{' && attrsEnd.find(p)) {
                attrs = doc.substring(p, attrsEnd.start() + 1);
                end = attrsEnd;
            } else if (tail.region(p, doc.length()).lookingAt()) {
                end = tail;
            } else {
                continue;
            }
            String ns = head.group("namespace");
            return new Token(
                    head.start(),
                    end.end(),
                    head.group("closer") != null,
                    end.group("void") != null,
                    (ns == null ? "core/" : ns) + head.group("name"),
                    attrs
            );
        }
        return null;
    }

    private boolean step() {
        var t = nextToken();
        if (t == null) {
            if (stack.isEmpty()) {
                addFreeform();
            } else {
                // implicit closers
                while (!stack.isEmpty()) {
                    addBlockFromStack(-1);
                }
            }
            return false;
        }
        int start = t.start(), length = t.end() - start;
        var block = newBlock(t);
        if (t.closer()) {
            if (stack.isEmpty()) {
                addFreeform();
                return false;
            }
            if (stack.size() == 1) {
                addBlockFromStack(start);
            } else {
                var top = stack.pop();
                appendHtml(top.block, doc.substring(top.prevOffset[0], start));
                addInnerBlock(top.block, top.tokenStart, top.tokenLength, start + length);
            }
        } else if (t.isVoid()) {
            if (stack.isEmpty()) {
                if (start > offset) {
                    output.add(freeform(doc.substring(offset, start)));
                }
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

    private static Block newBlock(Token t) {
        var name = t.name();
        JsonNode attrs = null;
        if (t.attrs() != null) {
            try {
                attrs = MAPPER.readTree(t.attrs());
            } catch (Exception e) {
                /* invalid JSON -> empty */
            }
        }
        if (attrs == null || !attrs.isObject()) {
            attrs = MAPPER.createObjectNode();
        }
        return new Block(name, attrs, new ArrayList<>(), new ArrayList<>());
    }

    private static Block freeform(String html) {
        return new Block(null, MAPPER.createObjectNode(), new ArrayList<>(), new ArrayList<>(List.of(html)));
    }

    private static void appendHtml(Block b, String html) {
        if (!html.isEmpty()) {
            b.innerContent().add(html);
        }
    }

    private void addFreeform() {
        if (offset < doc.length()) {
            output.add(freeform(doc.substring(offset)));
        }
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
        if (top.leadingStart >= 0) {
            output.add(freeform(doc.substring(top.leadingStart, top.tokenStart)));
        }
        output.add(top.block);
    }
}
