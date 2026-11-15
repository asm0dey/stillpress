package site.asm0dey.stillpress.store;

public record Term(long id, String taxonomy, String name, String slug, Long parentId, String description) {}
