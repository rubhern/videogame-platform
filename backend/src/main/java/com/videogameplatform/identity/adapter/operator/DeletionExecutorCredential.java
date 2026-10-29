package com.videogameplatform.identity.adapter.operator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Dedicated management capability, never an OIDC or Keycloak administration credential. */
public final class DeletionExecutorCredential {
    private final String token;

    public DeletionExecutorCredential(boolean enabled, String file) {
        if (!enabled) {
            token = "";
            return;
        }
        try {
            Path path = Path.of(file);
            if (!path.isAbsolute()
                    || Files.isSymbolicLink(path)
                    || Files.size(path) > 128
                    || Files.getPosixFilePermissions(path).stream()
                            .anyMatch(
                                    p ->
                                            p.name().startsWith("GROUP_")
                                                    || p.name().startsWith("OTHERS_")))
                throw new IllegalStateException();
            token = Files.readString(path).strip();
            if (!token.matches("[0-9a-f]{64}")) throw new IllegalStateException();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Invalid protected account-deletion executor credential");
        }
    }

    public boolean enabled() {
        return !token.isEmpty();
    }

    public boolean accepts(String authorization) {
        return enabled()
                && authorization != null
                && authorization.length() == 71
                && MessageDigest.isEqual(
                        ("Bearer " + token).getBytes(StandardCharsets.US_ASCII),
                        authorization.getBytes(StandardCharsets.US_ASCII));
    }
}
