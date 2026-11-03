package site.asm0dey.stillpress.publish.views;

public record SiteView(String title, String description, String language, String homeUrl) {
    /**
     * Primary subtag, so {@code ru-RU} gives {@code ru}.
     */
    public String lang() {
        return language == null || language.isBlank() ? "en" : language.split("[-_]")[0];
    }
}
