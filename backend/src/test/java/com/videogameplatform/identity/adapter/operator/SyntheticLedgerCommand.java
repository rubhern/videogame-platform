package com.videogameplatform.identity.adapter.operator;

import com.videogameplatform.identity.application.AccountDeletionStore;
import com.videogameplatform.identity.application.AccountOperationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.ObjectMapper;

/** Test-only command seam for synthetic ledger fixtures; never packaged in the application. */
public final class SyntheticLedgerCommand {
    private final String executable;
    private final String issuer;
    private final ObjectMapper json;

    public SyntheticLedgerCommand(String executable, String issuer, ObjectMapper json) {
        this.executable = executable;
        this.issuer = issuer;
        this.json = json;
    }

    public boolean available() {
        return !executable.isBlank()
                && Path.of(executable).isAbsolute()
                && Files.isExecutable(Path.of(executable));
    }

    public void erase(AccountDeletionStore.Work work) {
        Process process = null;
        try {
            if (!available()) throw new IllegalStateException();
            process =
                    new ProcessBuilder(executable)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
            // The wrapper must enforce its own SSH deadline and fixed forced command.
            try (var input = process.getOutputStream()) {
                input.write(
                        json.writeValueAsBytes(
                                Map.of(
                                        "version",
                                        1,
                                        "issuer",
                                        issuer,
                                        "subject",
                                        work.subject(),
                                        "userId",
                                        work.userId(),
                                        "requestId",
                                        work.requestId())));
            }
            // Never block on stdout before timeout; receipt is at most one short line.
            Process active = process;
            var receipt =
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return active.getInputStream().readNBytes(4097);
                                } catch (java.io.IOException failure) {
                                    throw new IllegalStateException();
                                }
                            });
            if (!process.waitFor(600, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IllegalStateException();
            byte[] bytes = receipt.get(1, TimeUnit.SECONDS);
            if (bytes.length > 4096) throw new IllegalStateException();
            var result = json.readTree(bytes);
            if (!result.path("verified").isBoolean()
                    || !result.path("verified").asBoolean(false)
                    || !work.requestId().equals(result.path("requestId").asString())
                    || !work.userId().equals(result.path("userId").asString()))
                throw new IllegalStateException();
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AccountOperationException(AccountOperationException.Reason.UNAVAILABLE);
        } finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                try {
                    process.getInputStream().close();
                } catch (java.io.IOException ignored) {
                }
            }
        }
    }
}
