package site.asm0dey.stillpress.store;

import module java.base;

public record Passkey(
        String credentialId,
        long userId,
        String name,
        String publicKey,
        long algorithm,
        String aaguid,
        long signCount,
        LocalDateTime createdAt,
        LocalDateTime lastUsedAt
) {}
