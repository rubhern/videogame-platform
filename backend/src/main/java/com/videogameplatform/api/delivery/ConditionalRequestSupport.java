package com.videogameplatform.api.delivery;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Strong representation validators and RFC-compatible conditional GET matching. */
@Component
final class ConditionalRequestSupport {

    private final ObjectMapper objectMapper;

    ConditionalRequestSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String strongEntityTag(Object representation) {
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

    boolean matches(String ifNoneMatch, String entityTag) {
        return ifNoneMatch != null
                && Arrays.stream(ifNoneMatch.split(","))
                        .map(String::trim)
                        .anyMatch(
                                value ->
                                        "*".equals(value)
                                                || opaqueTag(value).equals(opaqueTag(entityTag)));
    }

    private static String opaqueTag(String value) {
        return value.startsWith("W/") ? value.substring(2) : value;
    }
}
