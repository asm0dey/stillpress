package site.asm0dey.stillpress.publish.views;

/**
 * A comment with the item it belongs to, for the site-wide comments feed.
 */
public record SiteComment(ItemView item, CommentView comment) {}
