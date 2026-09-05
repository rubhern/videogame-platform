package com.videogameplatform.api.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.mock.web.MockHttpServletResponse;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Focused runtime-response conformance checks backed by the reviewed OpenAPI source. */
public final class OpenApiResponseContract {

    private static final String OPERATION_METHOD = "get";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Map<String, Object> contract;
    private final String path;

    private OpenApiResponseContract(Map<String, Object> contract, String path) {
        this.contract = contract;
        this.path = path;
    }

    /** Loads the reviewed contract for one operation so assertions cannot drift from it. */
    public static OpenApiResponseContract load(String path) {
        Path source = findContractSource();
        try (InputStream input = Files.newInputStream(source)) {
            return new OpenApiResponseContract(asMap(new Yaml().load(input), "OpenAPI root"), path);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot read reviewed OpenAPI source " + source, exception);
        }
    }

    public void assertJsonResponse(
            HttpResponse<String> response, int expectedStatus, String expectedSchemaName) {
        assertJsonResponse(
                response.statusCode(),
                response.headers()::firstValue,
                response.body(),
                expectedStatus,
                expectedSchemaName);
    }

    public void assertJsonResponse(
            MockHttpServletResponse response, int expectedStatus, String expectedSchemaName) {
        assertJsonResponse(
                response.getStatus(),
                header -> Optional.ofNullable(response.getHeader(header)),
                new String(response.getContentAsByteArray(), StandardCharsets.UTF_8),
                expectedStatus,
                expectedSchemaName);
    }

    private void assertJsonResponse(
            int actualStatus,
            Function<String, Optional<String>> headerValue,
            String body,
            int expectedStatus,
            String expectedSchemaName) {
        assertThat(actualStatus).isEqualTo(expectedStatus);
        Map<String, Object> specification = responseSpecification(expectedStatus);
        assertDeclaredHeaders(headerValue, specification);

        String contentType = headerValue.apply("Content-Type").orElseThrow();
        String mediaType = contentType.split(";", 2)[0];
        Map<String, Object> content = asMap(specification.get("content"), "response content");
        assertThat(content).containsKey(mediaType);

        Map<String, Object> media = asMap(content.get(mediaType), mediaType);
        Map<String, Object> schema = asMap(media.get("schema"), mediaType + " schema");
        assertThat(schema.get("$ref")).isEqualTo("#/components/schemas/" + expectedSchemaName);
        validate(OBJECT_MAPPER.readTree(body), schema, "$response");
    }

    public void assertEmptyResponse(HttpResponse<String> response, int expectedStatus) {
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        assertThat(response.body()).isEmpty();
        Map<String, Object> specification = responseSpecification(expectedStatus);
        assertDeclaredHeaders(response.headers()::firstValue, specification);
        assertThat(specification).doesNotContainKey("content");
    }

    private Map<String, Object> responseSpecification(int status) {
        Map<String, Object> paths = asMap(contract.get("paths"), "paths");
        Map<String, Object> operation =
                asMap(asMap(paths.get(path), path).get(OPERATION_METHOD), "GET " + path);
        Map<String, Object> responses = asMap(operation.get("responses"), "responses");
        Map<String, Object> response =
                asMap(responses.get(Integer.toString(status)), "response " + status);
        return resolveReference(response);
    }

    private void assertDeclaredHeaders(
            Function<String, Optional<String>> headerValue, Map<String, Object> specification) {
        Map<String, Object> headers = optionalMap(specification.get("headers"));
        headers.entrySet().stream()
                .filter(
                        header ->
                                Boolean.TRUE.equals(
                                        resolveReference(
                                                        asMap(
                                                                header.getValue(),
                                                                "response header "
                                                                        + header.getKey()))
                                                .get("required")))
                .map(Map.Entry::getKey)
                .forEach(
                        header ->
                                assertThat(headerValue.apply(header))
                                        .as("OpenAPI response header %s", header)
                                        .isPresent());
    }

    private void validate(JsonNode value, Map<String, Object> rawSchema, String pointer) {
        Map<String, Object> schema = resolveReference(rawSchema);
        List<Object> alternatives = optionalList(schema.get("oneOf"));
        if (!alternatives.isEmpty()) {
            List<AssertionError> failures = new ArrayList<>();
            int matches = 0;
            for (Object alternative : alternatives) {
                try {
                    validate(value, asMap(alternative, pointer + " oneOf"), pointer);
                    matches++;
                } catch (AssertionError failure) {
                    failures.add(failure);
                }
            }
            assertThat(matches)
                    .as(
                            "%s must match exactly one OpenAPI oneOf branch; failures=%s",
                            pointer, failures)
                    .isEqualTo(1);
            return;
        }

        if (schema.containsKey("enum")) {
            assertThat(optionalList(schema.get("enum")))
                    .as("%s enum", pointer)
                    .contains(value.isString() ? value.stringValue() : value.toString());
        }
        if (schema.containsKey("const")) {
            assertThat(value.isString() ? value.stringValue() : value.toString())
                    .as("%s const", pointer)
                    .isEqualTo(String.valueOf(schema.get("const")));
        }

        String type = (String) schema.get("type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "object" -> validateObject(value, schema, pointer);
            case "array" -> validateArray(value, schema, pointer);
            case "string" -> validateString(value, schema, pointer);
            case "integer" -> validateNumber(value, schema, pointer, true);
            case "number" -> validateNumber(value, schema, pointer, false);
            case "boolean" -> assertThat(value.isBoolean()).as(pointer).isTrue();
            case "null" -> assertThat(value.isNull()).as(pointer).isTrue();
            default ->
                    throw new AssertionError("Unsupported OpenAPI type " + type + " at " + pointer);
        }
    }

    private void validateObject(JsonNode value, Map<String, Object> schema, String pointer) {
        assertThat(value.isObject()).as(pointer).isTrue();
        Map<String, Object> properties = optionalMap(schema.get("properties"));
        for (Object required : optionalList(schema.get("required"))) {
            assertThat(value.has(String.valueOf(required)))
                    .as("%s required property %s", pointer, required)
                    .isTrue();
        }
        if (Boolean.FALSE.equals(schema.get("additionalProperties"))
                || Boolean.FALSE.equals(schema.get("unevaluatedProperties"))) {
            Set<String> actual = new HashSet<>();
            actual.addAll(value.propertyNames());
            assertThat(actual).as(pointer + " properties").isSubsetOf(properties.keySet());
        }
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            if (value.has(property.getKey())) {
                validate(
                        value.get(property.getKey()),
                        asMap(property.getValue(), pointer + "/" + property.getKey()),
                        pointer + "/" + property.getKey());
            }
        }
    }

    private void validateArray(JsonNode value, Map<String, Object> schema, String pointer) {
        assertThat(value.isArray()).as(pointer).isTrue();
        Number minimum = (Number) schema.get("minItems");
        if (minimum != null) {
            assertThat(value.size())
                    .as(pointer + " size")
                    .isGreaterThanOrEqualTo(minimum.intValue());
        }
        Map<String, Object> itemSchema = optionalMap(schema.get("items"));
        for (int index = 0; index < value.size(); index++) {
            validate(value.get(index), itemSchema, pointer + "/" + index);
        }
        if (Boolean.TRUE.equals(schema.get("uniqueItems"))) {
            Set<JsonNode> unique = new HashSet<>();
            value.forEach(unique::add);
            assertThat(unique).as(pointer + " unique items").hasSize(value.size());
        }
    }

    private void validateString(JsonNode value, Map<String, Object> schema, String pointer) {
        assertThat(value.isString()).as(pointer).isTrue();
        String text = value.stringValue();
        Number minimum = (Number) schema.get("minLength");
        Number maximum = (Number) schema.get("maxLength");
        if (minimum != null) {
            assertThat(text.codePointCount(0, text.length()))
                    .as(pointer + " length")
                    .isGreaterThanOrEqualTo(minimum.intValue());
        }
        if (maximum != null) {
            assertThat(text.codePointCount(0, text.length()))
                    .as(pointer + " length")
                    .isLessThanOrEqualTo(maximum.intValue());
        }
        if (schema.get("pattern") instanceof String pattern) {
            assertThat(Pattern.compile(pattern).matcher(text).find())
                    .as(pointer + " pattern")
                    .isTrue();
        }
        switch (String.valueOf(schema.get("format"))) {
            case "date" -> LocalDate.parse(text);
            case "date-time" -> OffsetDateTime.parse(text);
            case "uri" -> assertThat(URI.create(text).isAbsolute()).as(pointer + " URI").isTrue();
            case "uri-reference" -> URI.create(text);
            default -> {
                // No additional format assertion is required.
            }
        }
    }

    private static void validateNumber(
            JsonNode value, Map<String, Object> schema, String pointer, boolean integral) {
        assertThat(integral ? value.isIntegralNumber() : value.isNumber()).as(pointer).isTrue();
        double number = value.doubleValue();
        if (schema.get("minimum") instanceof Number minimum) {
            assertThat(number)
                    .as(pointer + " minimum")
                    .isGreaterThanOrEqualTo(minimum.doubleValue());
        }
        if (schema.get("maximum") instanceof Number maximum) {
            assertThat(number).as(pointer + " maximum").isLessThanOrEqualTo(maximum.doubleValue());
        }
    }

    private Map<String, Object> resolveReference(Map<String, Object> value) {
        Object reference = value.get("$ref");
        if (!(reference instanceof String pointer)) {
            return value;
        }
        Object resolved = contract;
        for (String segment : pointer.substring(2).split("/")) {
            resolved = asMap(resolved, pointer).get(segment);
        }
        return asMap(resolved, pointer);
    }

    private static Path findContractSource() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("docs/architecture/api/openapi.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Cannot locate docs/architecture/api/openapi.yaml");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String description) {
        assertThat(value).as(description).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> optionalMap(Object value) {
        return value == null ? Map.of() : asMap(value, "OpenAPI map");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> optionalList(Object value) {
        if (value == null) {
            return List.of();
        }
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }
}
