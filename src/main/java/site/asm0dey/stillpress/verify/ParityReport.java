package site.asm0dey.stillpress.verify;

import module java.base;

public record ParityReport(int checked, List<Mismatch> mismatches) {
    public record Mismatch(String path, int sourceStatus, int targetStatus, String detail) {}

    public String toText() {
        var sb = new StringBuilder("Checked %d URLs, %d mismatches%n".formatted(checked, mismatches.size()));
        for (var m : mismatches) {
            sb.append("  %s source=%d target=%d %s%n"
                .formatted(m.path(), m.sourceStatus(), m.targetStatus(), m.detail())
            );
        }
        return sb.toString();
    }
}
