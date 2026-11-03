package site.asm0dey.stillpress.verify;

import module java.base;

public record ParityReport(int checked, List<Mismatch> mismatches) {
    public enum Cause {
        TARGET_404("target 404"),
        TARGET_OTHER_STATUS("target other status"),
        DIFFERENT_FINAL_PATH("different final path"),
        CROSS_HOST("cross-host redirect"),
        REDIRECT_LOOP("redirect loop"),
        NETWORK_ERROR("network error"),
        SOURCE_FETCH_FAILURE("source fetch failure");
        final String label;

        Cause(String label) {
            this.label = label;
        }
    }

    public record Mismatch(String path, int sourceStatus, int targetStatus, String detail, Cause cause) {}

    /**
     * The run passed: something was checked and nothing differed or failed to fetch.
     */
    public boolean ok() {
        return checked > 0 && mismatches.isEmpty();
    }

    private static String status(int s) {
        return s < 0 ? "-" : Integer.toString(s);
    }

    public String toText() {
        var sb = new StringBuilder("Checked %d URLs, %d mismatches%n".formatted(checked, mismatches.size()));
        if (checked == 0) {
            sb.append("WARNING: no URLs were checked%n".formatted());
        }
        for (var c : Cause.values()) {
            var group = mismatches.stream().filter(m -> m.cause() == c).toList();
            if (group.isEmpty()) {
                continue;
            }
            sb.append("%s (%d):%n".formatted(c.label, group.size()));
            for (var m : group) {
                sb.append("  %s source=%s target=%s %s%n"
                    .formatted(m.path(), status(m.sourceStatus()), status(m.targetStatus()), m.detail())
                );
            }
        }
        return sb.toString();
    }
}
