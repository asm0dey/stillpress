package site.asm0dey.stillpress.blocks;

import module java.base;

/** Classic (non-block) post content: [caption] shortcode plus a port of WordPress wpautop(). Other shortcodes stay verbatim. */
public final class ClassicContent {
  private ClassicContent() {}

  private static final String ALL_BLOCKS = "(?:table|thead|tfoot|caption|col|colgroup|tbody|tr|td|th|div|dl|dd|dt|ul|ol|li|pre|form|map|area|blockquote|address|math|style|p|h[1-6]|hr|fieldset|legend|section|article|aside|hgroup|header|footer|nav|figure|figcaption|details|menu|summary)";
  private static final Pattern CAPTION = Pattern.compile("\\[caption((?:\\s[^\\]]*)?)\\](.*?)\\[/caption\\]", Pattern.DOTALL);
  private static final Pattern ATTR = Pattern.compile("(\\w+)=\"([^\"]*)\"");
  private static final Pattern TAG = Pattern.compile("<[^<>]*>");
  private static final Pattern PRESERVE = Pattern.compile("<(script|style|svg|math).*?</\\1>", Pattern.DOTALL);

  public static String toHtml(String classicHtml) {
    return autop(captions(classicHtml));
  }

  private static String captions(String s) {
    return CAPTION.matcher(s).replaceAll(r -> {
      var attrs = new HashMap<String, String>();
      var am = ATTR.matcher(r.group(1));
      while (am.find()) attrs.put(am.group(1), am.group(2));
      var body = r.group(2);
      int gt = body.lastIndexOf('>');
      var media = gt < 0 ? "" : body.substring(0, gt + 1);
      var text = (gt < 0 ? body : body.substring(gt + 1)).trim();
      if (attrs.containsKey("caption")) text = attrs.get("caption");
      var id = attrs.containsKey("id") ? " id=\"" + attrs.get("id") + "\"" : "";
      return Matcher.quoteReplacement("<figure class=\"wp-caption\"" + id + ">" + media
        + "<figcaption class=\"wp-caption-text\">" + text + "</figcaption></figure>");
    });
  }

  /** wpautop($text, br=true). Not ported: the &lt;option&gt; and &lt;object&gt; whitespace special-cases. */
  static String autop(String text) {
    if (text.trim().isEmpty()) return "";
    text += "\n";
    var pre = new LinkedHashMap<String, String>();
    if (text.contains("<pre")) {
      var parts = text.split("</pre>", -1);
      var sb = new StringBuilder();
      for (int i = 0; i < parts.length - 1; i++) {
        int start = parts[i].indexOf("<pre");
        if (start < 0) { sb.append(parts[i]); continue; }
        var name = "<pre wp-pre-tag-" + pre.size() + "></pre>";
        pre.put(name, parts[i].substring(start) + "</pre>");
        sb.append(parts[i], 0, start).append(name);
      }
      text = sb.append(parts[parts.length - 1]).toString();
    }
    text = text.replaceAll("<br\\s*/?>\\s*<br\\s*/?>", "\n\n");
    text = text.replaceAll("(<" + ALL_BLOCKS + "[\\s/>])", "\n\n$1");
    text = text.replaceAll("(</" + ALL_BLOCKS + ">)", "$1\n\n");
    text = text.replace("\r\n", "\n").replace("\r", "\n");
    // newlines inside tags must survive paragraph splitting
    text = TAG.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group().replace("\n", " <!-- wpnl --> ")));
    text = text.replaceAll("\n\n+", "\n\n");
    var sb = new StringBuilder();
    for (var t : text.split("\n\\s*\n")) {
      if (t.isEmpty()) continue;
      sb.append("<p>").append(t.replaceAll("^\n+|\n+$", "")).append("</p>\n");
    }
    text = sb.toString();
    text = text.replaceAll("<p>\\s*</p>", "");
    text = text.replaceAll("<p>([^<]+)</(div|address|form)>", "<p>$1</p></$2>");
    text = text.replaceAll("<p>\\s*(</?" + ALL_BLOCKS + "[^>]*>)\\s*</p>", "$1");
    text = text.replaceAll("<p>(<li.+?)</p>", "$1");
    text = text.replaceAll("(?i)<p><blockquote([^>]*)>", "<blockquote$1><p>");
    text = text.replace("</blockquote></p>", "</p></blockquote>");
    text = text.replaceAll("<p>\\s*(</?" + ALL_BLOCKS + "[^>]*>)", "$1");
    text = text.replaceAll("(</?" + ALL_BLOCKS + "[^>]*>)\\s*</p>", "$1");
    text = PRESERVE.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group().replace("\n", "<WPPreserveNewline />")));
    text = text.replace("<br>", "<br />").replace("<br/>", "<br />");
    text = text.replaceAll("(?<!<br />)\\s*\n", "<br />\n");
    text = text.replace("<WPPreserveNewline />", "\n");
    text = text.replaceAll("(</?" + ALL_BLOCKS + "[^>]*>)\\s*<br />", "$1");
    text = text.replaceAll("<br />(\\s*</?(?:p|li|div|dl|dd|dt|th|pre|td|ul|ol)[^>]*>)", "$1");
    text = text.replaceAll("\n</p>$", "</p>");
    for (var e : pre.entrySet()) text = text.replace(e.getKey(), e.getValue());
    return text.replace(" <!-- wpnl --> ", "\n").replace("<!-- wpnl -->", "\n");
  }
}
