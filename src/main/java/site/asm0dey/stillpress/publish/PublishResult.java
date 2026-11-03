package site.asm0dey.stillpress.publish;

import module java.base;

public record PublishResult(Path release, int files, Duration took) {}
