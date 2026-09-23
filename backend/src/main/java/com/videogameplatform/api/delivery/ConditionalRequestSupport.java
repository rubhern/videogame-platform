package com.videogameplatform.api.delivery;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.ETag;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Strong representation validators and RFC-compatible conditional GET matching. */
@Component
public final class ConditionalRequestSupport {

    private final ObjectMapper objectMapper;

    public ConditionalRequestSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String strongEntityTag(Object representation) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(representation);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return '"' + HexFormat.of().formatHex(digest) + '"';
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "The API representation cannot be serialized", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    public boolean matches(String ifNoneMatch, String entityTag) {
        if (ifNoneMatch == null) {
            return false;
        }
        ETag responseTag = ETag.create(entityTag);
        return ETag.parse(ifNoneMatch).stream()
                .anyMatch(
                        requestTag ->
                                requestTag.isWildcard() || requestTag.compare(responseTag, false));
    }
}
