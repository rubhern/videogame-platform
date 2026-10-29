package com.videogameplatform.identity.adapter.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.videogameplatform.identity.adapter.web.SessionRevocation;
import com.videogameplatform.identity.application.AccountDeletionStore;
import com.videogameplatform.identity.domain.UserId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountDeletionExecutorEndpointTest {
    @TempDir Path directory;
    final AccountDeletionStore store = mock(AccountDeletionStore.class);
    final SessionRevocation sessions = mock(SessionRevocation.class);
    final Instant now = Instant.parse("2026-10-28T12:00:00Z");
    final AccountDeletionStore.Work work =
            new AccountDeletionStore.Work(
                    UserId.fromIssuerAndSubject("issuer", "subject").value(),
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString());

    AccountDeletionExecutorEndpoint endpoint(String authorization) throws Exception {
        when(store.requiresIndependentEvidence()).thenReturn(true);
        Path key = directory.resolve("capability");
        Files.writeString(key, "a".repeat(64));
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
        return new AccountDeletionExecutorEndpoint(
                store,
                sessions,
                new DeletionExecutorCredential(true, key.toString()),
                () -> authorization,
                Clock.fixed(now, ZoneOffset.UTC),
                "issuer",
                new SimpleMeterRegistry());
    }

    @Test
    void privateDevCanAcknowledgeVerifiedAbsenceWithoutPretendingToHaveIndependentEvidence()
            throws Exception {
        var endpoint = endpoint("Bearer " + "a".repeat(64));
        when(store.requiresIndependentEvidence()).thenReturn(false);
        var claim =
                new AccountDeletionStore.Work(work.userId(), "", work.requestId(), work.claim());
        when(store.acknowledge(claim, true, null, null, now)).thenReturn(true);
        assertThat(
                        endpoint.exchange(
                                        "finish",
                                        work.userId(),
                                        work.requestId(),
                                        work.claim(),
                                        true,
                                        null,
                                        null)
                                .getStatus())
                .isEqualTo(200);
        assertThat(
                        endpoint.exchange(
                                        "finish",
                                        work.userId(),
                                        work.requestId(),
                                        work.claim(),
                                        true,
                                        "a".repeat(64),
                                        "b".repeat(64))
                                .getStatus())
                .isEqualTo(400);
    }

    @Test
    void aWrongCapabilityCannotObserveOrClaimAnyAccount() throws Exception {
        assertThat(
                        endpoint("Bearer " + "b".repeat(64))
                                .exchange("claim", null, null, null, null, null, null)
                                .getStatus())
                .isEqualTo(401);
        verifyNoInteractions(store, sessions);
    }

    @Test
    void incompleteWriteDrainReleasesTheClaimWithoutAuthorizingErasure() throws Exception {
        when(store.claim(now)).thenReturn(Optional.of(work));
        when(sessions.revoke(new UserId(work.userId())))
                .thenReturn(new SessionRevocation.Result(2, false));
        assertThat(
                        endpoint("Bearer " + "a".repeat(64))
                                .exchange("claim", null, null, null, null, null, null)
                                .getStatus())
                .isEqualTo(503);
        verify(store).finish(work, false, now);
    }

    @Test
    void completeAcknowledgementRequiresEvidenceAndAnOwnedLiveClaim() throws Exception {
        var endpoint = endpoint("Bearer " + "a".repeat(64));
        assertThat(
                        endpoint.exchange(
                                        "finish",
                                        work.userId(),
                                        work.requestId(),
                                        work.claim(),
                                        true,
                                        null,
                                        null)
                                .getStatus())
                .isEqualTo(400);
        verify(store, never()).acknowledge(work, true, null, null, now);
        assertThat(
                        endpoint.exchange(
                                        "finish",
                                        work.userId(),
                                        work.requestId(),
                                        work.claim(),
                                        true,
                                        "a".repeat(64),
                                        "b".repeat(64))
                                .getStatus())
                .isEqualTo(409);
    }
}
