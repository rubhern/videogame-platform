package com.videogameplatform.api.delivery.profile;

import com.videogameplatform.api.generated.ProfileApi;
import com.videogameplatform.api.generated.model.AccountDeletionConfirmation;
import com.videogameplatform.api.generated.model.AccountDeletionStatus;
import com.videogameplatform.api.generated.model.PrivateProfile;
import com.videogameplatform.catalogue.application.preferences.ExportPlatformPreferences;
import com.videogameplatform.identity.application.AccountDeletionStore;
import com.videogameplatform.identity.application.AccountOperationException;
import com.videogameplatform.identity.application.CurrentUser;
import com.videogameplatform.identity.application.CurrentUserUnavailableException;
import com.videogameplatform.identity.application.DeletionExecutorAvailability;
import com.videogameplatform.ratings.application.ExportPersonalRatings;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** Principal-only private delivery. A successful download is completely prepared before headers. */
@RestController
@RequestMapping("/api/v1")
public class ProfileController implements ProfileApi {
    private static final String RECEIPT = "gameometro_deletion";
    private static final SecureRandom RECEIPT_RANDOM = new SecureRandom();
    private final CurrentUser user;
    private final AccountDeletionStore deletions;
    private final DeletionExecutorAvailability erasure;
    private final ExportPersonalRatings ratings;
    private final ExportPlatformPreferences preferences;
    private final HttpServletRequest request;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean secure;

    public ProfileController(
            CurrentUser user,
            AccountDeletionStore deletions,
            DeletionExecutorAvailability erasure,
            ExportPersonalRatings ratings,
            ExportPlatformPreferences preferences,
            HttpServletRequest request,
            ObjectMapper json,
            Clock clock,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        this.user = user;
        this.deletions = deletions;
        this.erasure = erasure;
        this.ratings = ratings;
        this.preferences = preferences;
        this.request = request;
        this.json = json;
        this.clock = clock;
        this.secure = secure;
    }

    @Override
    public ResponseEntity<PrivateProfile> getMyProfile() {
        requireUser();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profile());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 30)
    public ResponseEntity<Resource> exportMyData() {
        String owner = requireUser();
        try {
            var output = new LimitedOutput();
            try (var generator = json.createGenerator(output)) {
                generator.writeStartObject();
                generator.writeStringProperty("format", "gameometro-personal-data-export");
                generator.writeNumberProperty("version", 1);
                generator.writeStringProperty("exportedAt", clock.instant().toString());
                generator.writeName("account");
                generator.writeStartObject();
                var account = profile();
                if (account.getName() != null)
                    generator.writeStringProperty("name", account.getName());
                if (account.getEmail() != null)
                    generator.writeStringProperty("email", account.getEmail());
                generator.writeEndObject();
                generator.writeArrayPropertyStart("ratings");
                ratings.read(
                        owner,
                        item -> {
                            generator.writeStartObject();
                            generator.writeStringProperty("gameId", item.gameId());
                            generator.writePOJOProperty("title", item.title());
                            var rating = item.rating();
                            generator.writePOJOProperty("value", rating.value());
                            generator.writePOJOProperty("platforms", rating.platforms());
                            generator.writeStringProperty(
                                    "createdAt", rating.createdAt().toString());
                            generator.writeStringProperty(
                                    "updatedAt", rating.updatedAt().toString());
                            generator.writeEndObject();
                        });
                generator.writeEndArray();
                var choice = preferences.read(owner);
                generator.writeObjectPropertyStart("preferredPlatforms");
                generator.writeBooleanProperty("configured", choice.preferences().configured());
                if (choice.savedAt() != null)
                    generator.writeStringProperty("savedAt", choice.savedAt().toString());
                generator.writePOJOProperty("platforms", choice.preferences().platforms());
                generator.writeEndObject();
                generator.writeEndObject();
            }
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"gameometro-datos.json\"")
                    .body(new ByteArrayResource(output.toByteArray()));
        } catch (RuntimeException failure) {
            throw new AccountOperationException(
                    AccountOperationException.Reason.UNAVAILABLE, failure);
        }
    }

    @Override
    public ResponseEntity<AccountDeletionStatus> requestMyAccountDeletion(
            String csrfToken, AccountDeletionConfirmation confirmation) {
        requireUser();
        if (!request.getParameterMap().isEmpty())
            throw new com.videogameplatform.api.delivery.ApiRequestException(
                    com.videogameplatform.api.generated.model.ProblemCode.REQUEST_PARAMETER_UNKNOWN,
                    "/query");
        OidcUser principal = principal();
        Instant authenticated;
        try {
            authenticated = principal.getIdToken().getAuthenticatedAt();
        } catch (RuntimeException invalid) {
            authenticated = null;
        }
        Instant now = clock.instant();
        if (authenticated == null
                || authenticated.isAfter(now)
                || authenticated.isBefore(now.minusSeconds(300)))
            throw new AccountOperationException(
                    AccountOperationException.Reason.RECENT_AUTHENTICATION);
        if (!erasure.available())
            throw new AccountOperationException(AccountOperationException.Reason.UNAVAILABLE);
        // Generated enum validation and strict JSON reject all other/unknown command properties.
        if (confirmation.getConfirmation() != AccountDeletionConfirmation.ConfirmationEnum.ELIMINAR)
            throw new IllegalArgumentException("Explicit confirmation required");
        byte[] entropy = new byte[32];
        RECEIPT_RANDOM.nextBytes(entropy);
        String receipt = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        user.changeAsCurrentUser(
                id -> {
                    if (!deletions.admit(
                            id,
                            principal.getSubject(),
                            UUID.randomUUID().toString(),
                            hash(receipt),
                            now)) throw new CurrentUserUnavailableException();
                    return null;
                });
        String cookie =
                ResponseCookie.from(RECEIPT, receipt)
                        .httpOnly(true)
                        .secure(secure)
                        .sameSite("Strict")
                        .path("/api/v1/account-deletion")
                        .maxAge(Duration.ofHours(24))
                        .build()
                        .toString();
        // The committed fence already prevents every new authenticated operation. End this
        // browser session immediately; protected processing and global revocation run separately.
        var currentSession = request.getSession(false);
        if (currentSession != null) currentSession.invalidate();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie)
                .body(new AccountDeletionStatus(AccountDeletionStatus.StateEnum.PENDING));
    }

    @Override
    public ResponseEntity<AccountDeletionStatus> getAccountDeletionStatus() {
        String receipt = null;
        if (request.getCookies() != null)
            for (var cookie : request.getCookies()) {
                if (RECEIPT.equals(cookie.getName())) receipt = cookie.getValue();
            }
        if (receipt == null || !receipt.matches("[A-Za-z0-9_-]{43}"))
            throw new CurrentUserUnavailableException();
        String state =
                deletions
                        .status(hash(receipt), clock.instant())
                        .orElseThrow(CurrentUserUnavailableException::new);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new AccountDeletionStatus(AccountDeletionStatus.StateEnum.fromValue(state)));
    }

    private PrivateProfile profile() {
        var token = principal().getIdToken();
        return new PrivateProfile(erasure.available())
                .name(optional(token.getFullName()))
                .email(optional(token.getEmail()));
    }

    private static String optional(String value) {
        return value == null || value.isBlank() || value.length() > 500 ? null : value;
    }

    private String requireUser() {
        return user.currentUserId().orElseThrow(CurrentUserUnavailableException::new);
    }

    private static OidcUser principal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser oidc))
            throw new CurrentUserUnavailableException();
        return oidc;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class LimitedOutput extends ByteArrayOutputStream {
        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            if (count + length > 16 * 1024 * 1024)
                throw new IllegalStateException("Export byte budget exceeded");
            super.write(bytes, offset, length);
        }

        @Override
        public synchronized void write(int value) {
            if (count >= 16 * 1024 * 1024)
                throw new IllegalStateException("Export byte budget exceeded");
            super.write(value);
        }
    }
}
