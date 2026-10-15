package com.videogameplatform.identity.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.identity.application.CurrentUserUnavailableException;
import com.videogameplatform.identity.domain.UserId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class SessionRevocationTest {

    private static final String ISSUER = "https://identity.example/realms/test";
    private static final UserId ALICE = UserId.fromIssuerAndSubject(ISSUER, "alice");
    private static final Duration GENEROUS = Duration.ofSeconds(10);

    private final SessionRegistryImpl registry = new SessionRegistryImpl();
    private final ExecutorService requests = Executors.newCachedThreadPool();

    @AfterEach
    void stopRequests() {
        requests.shutdownNow();
    }

    @Test
    void expiresEverySessionOfOneUserOnEveryDeviceAndNoOtherUsersSession() {
        registry.registerNewSession("alice-laptop", signIn("alice"));
        registry.registerNewSession("alice-phone", signIn("alice"));
        registry.registerNewSession("bob-laptop", signIn("bob"));
        registry.registerNewSession("other-issuer", signIn("https://other.example", "alice"));
        SessionRevocation revocation = new SessionRevocation(registry, GENEROUS);

        assertThat(revocation.revoke(ALICE)).isEqualTo(new SessionRevocation.Result(2, true));

        assertThat(registry.getSessionInformation("alice-laptop").isExpired()).isTrue();
        assertThat(registry.getSessionInformation("alice-phone").isExpired()).isTrue();
        assertThat(registry.getSessionInformation("bob-laptop").isExpired()).isFalse();
        assertThat(registry.getSessionInformation("other-issuer").isExpired()).isFalse();
        // Idempotent: nothing is active any more, so the verification run reports zero.
        assertThat(revocation.revoke(ALICE)).isEqualTo(new SessionRevocation.Result(0, true));
        assertThat(revocation.whileActive("bob-laptop", () -> "bob writes"))
                .isEqualTo("bob writes");
    }

    @Test
    void refusesChangesForRevokedEndedOrUnknownSessionsBeforeRunningThem() {
        registry.registerNewSession("alice-laptop", signIn("alice"));
        registry.registerNewSession("alice-phone", signIn("alice"));
        SessionRevocation revocation = new SessionRevocation(registry, GENEROUS);
        revocation.revoke(ALICE);
        registry.removeSessionInformation("alice-phone");
        List<String> executed = new CopyOnWriteArrayList<>();

        for (String session : List.of("alice-laptop", "alice-phone", "never-registered")) {
            assertThatThrownBy(() -> revocation.whileActive(session, () -> executed.add(session)))
                    .isInstanceOf(CurrentUserUnavailableException.class);
        }
        assertThat(executed).isEmpty();
    }

    @Test
    void waitsForARunningChangeAndRefusesEveryChangeThatStartsAfterRevocation() throws Exception {
        registry.registerNewSession("alice-laptop", signIn("alice"));
        registry.registerNewSession("alice-phone", signIn("alice"));
        registry.registerNewSession("bob-laptop", signIn("bob"));
        SessionRevocation revocation = new SessionRevocation(registry, GENEROUS);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        CompletableFuture<String> inFlight =
                CompletableFuture.supplyAsync(
                        () ->
                                revocation.whileActive(
                                        "alice-laptop",
                                        () -> {
                                            writing.countDown();
                                            await(commit);
                                            return "committed";
                                        }),
                        requests);
        assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<SessionRevocation.Result> revoking = new CompletableFuture<>();
        Thread revoker = new Thread(() -> revoking.complete(revocation.revoke(ALICE)));
        revoker.start();
        awaitWaiting(revoker);
        // While revocation waits, a new change from any user waits too rather than overtaking it.
        CompletableFuture<String> bobWrite =
                CompletableFuture.supplyAsync(
                        () -> revocation.whileActive("bob-laptop", () -> "bob committed"),
                        requests);
        Thread.sleep(200);
        assertThat(revoking).isNotDone();
        assertThat(bobWrite).isNotDone();

        commit.countDown();

        assertThat(inFlight.get(5, TimeUnit.SECONDS)).isEqualTo("committed");
        assertThat(revoking.get(5, TimeUnit.SECONDS))
                .isEqualTo(new SessionRevocation.Result(2, true));
        assertThat(bobWrite.get(5, TimeUnit.SECONDS)).isEqualTo("bob committed");
        assertThatThrownBy(() -> revocation.whileActive("alice-laptop", () -> "late"))
                .isInstanceOf(CurrentUserUnavailableException.class);
        assertThatThrownBy(() -> revocation.whileActive("alice-phone", () -> "late"))
                .isInstanceOf(CurrentUserUnavailableException.class);
    }

    @Test
    void reportsAnUnsettledRevocationWhenARunningChangeOutlastsTheTimeout() throws Exception {
        registry.registerNewSession("alice-laptop", signIn("alice"));
        SessionRevocation revocation = new SessionRevocation(registry, Duration.ofMillis(150));
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CompletableFuture<String> stuck =
                CompletableFuture.supplyAsync(
                        () ->
                                revocation.whileActive(
                                        "alice-laptop",
                                        () -> {
                                            writing.countDown();
                                            await(commit);
                                            return "committed";
                                        }),
                        requests);
        assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();

        SessionRevocation.Result result = revocation.revoke(ALICE);

        // Sessions still end, but only a restart could guarantee the running write is gone.
        assertThat(result).isEqualTo(new SessionRevocation.Result(1, false));
        assertThat(registry.getSessionInformation("alice-laptop").isExpired()).isTrue();
        commit.countDown();
        assertThat(stuck.get(5, TimeUnit.SECONDS)).isEqualTo("committed");
    }

    private static void awaitWaiting(Thread revoker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (revoker.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Revocation never waited for the running change");
            }
            Thread.onSpinWait();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Test latch was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static DefaultOidcUser signIn(String subject) {
        return signIn(ISSUER, subject);
    }

    private static DefaultOidcUser signIn(String issuer, String subject) {
        Instant now = Instant.now();
        OidcIdToken token =
                OidcIdToken.withTokenValue("id-token-" + subject + "-" + System.nanoTime())
                        .issuer(issuer)
                        .subject(subject)
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("nonce", Long.toString(System.nanoTime()))
                        .build();
        return new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), token);
    }
}
