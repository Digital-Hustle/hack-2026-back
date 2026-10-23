package ru.cashflow.authprofile.units.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import io.swagger.v3.parser.OpenAPIV3Parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import ru.cashflow.authprofile.api.dto.response.ApiProblemRs;
import ru.cashflow.authprofile.api.dto.response.AuthSessionRs;
import ru.cashflow.authprofile.api.dto.response.CodeChallengeRs;
import ru.cashflow.authprofile.api.dto.response.CsrfTokenRs;
import ru.cashflow.authprofile.api.dto.response.FieldErrorRs;
import ru.cashflow.authprofile.api.dto.response.ProfileRs;
import ru.cashflow.authprofile.api.dto.response.SessionRs;
import ru.cashflow.authprofile.api.dto.response.SessionsRs;
import ru.cashflow.authprofile.api.error.ErrorCode;
import ru.cashflow.authprofile.api.json.AuthProfileJson;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

class OpenApiContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private String yaml;
    private JsonNode document;

    @BeforeEach
    void loadContract() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/openapi/auth-profile.yaml")) {
            assertThat(stream).as("Auth/Profile OpenAPI must ship in the API artifact").isNotNull();
            yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            document = new ObjectMapper(new YAMLFactory()).readTree(yaml);
        }
    }

    @Test
    void specificationHasNoUnresolvedReferencesOrStructuralErrors() {
        var parsed = new OpenAPIV3Parser().readContents(yaml, null, null);
        assertThat(parsed.getOpenAPI()).isNotNull();
        assertThat(parsed.getMessages()).isEmpty();
    }

    @Test
    void publishedExamplesAreValidPayloads() {
        document.at("/components/schemas")
                .properties()
                .forEach(
                        entry -> {
                            var example = entry.getValue().get("example");
                            if (example != null) assertSchema(entry.getKey(), example);
                        });
    }

    @Test
    void javaResponsesSerializeToThePublishedFrontendSchemas() {
        var json = new ObjectMapper();
        AuthProfileJson.configure(json);
        var id = UUID.fromString("11111111-1111-4111-8111-111111111111");
        var now = Instant.parse("2026-10-24T09:00:00Z");
        var user =
                ProfileRs.builder()
                        .id(id)
                        .email("user@example.com")
                        .displayName(null)
                        .version(0)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
        assertSchema("ProfileRs", json.valueToTree(user));
        assertSchema(
                "CodeChallengeRs",
                json.valueToTree(
                        CodeChallengeRs.builder()
                                .challengeId(id)
                                .expiresAt(now.plusSeconds(300))
                                .resendAvailableAt(now.plusSeconds(60))
                                .codeLength(6)
                                .build()));
        assertSchema(
                "CsrfTokenRs",
                json.valueToTree(
                        CsrfTokenRs.builder()
                                .token("opaque-csrf-token")
                                .headerName("X-CSRF-Token")
                                .build()));
        assertSchema(
                "AuthSessionRs",
                json.valueToTree(
                        AuthSessionRs.builder()
                                .sessionId(id)
                                .accessExpiresAt(now.plusSeconds(900))
                                .refreshExpiresAt(now.plusSeconds(3600))
                                .user(user)
                                .build()));
        assertSchema(
                "SessionsRs",
                json.valueToTree(
                        SessionsRs.builder()
                                .items(
                                        List.of(
                                                SessionRs.builder()
                                                        .id(id)
                                                        .createdAt(now)
                                                        .lastSeenAt(now)
                                                        .expiresAt(now.plusSeconds(3600))
                                                        .current(true)
                                                        .build()))
                                .total(1)
                                .build()));
        assertSchema(
                "ApiProblemRs",
                json.valueToTree(
                        ApiProblemRs.builder()
                                .type(URI.create("about:blank"))
                                .title("Validation failed")
                                .status(400)
                                .detail("Request validation failed")
                                .instance(URI.create("/api/v1/auth-profile/profile/me"))
                                .code(ErrorCode.VALIDATION_ERROR)
                                .traceId("e2f819a9c0d54c68")
                                .errors(
                                        List.of(
                                                FieldErrorRs.builder()
                                                        .field("displayName")
                                                        .code("NotBlank")
                                                        .message("Must not be blank")
                                                        .build()))
                                .build()));
    }

    private void assertSchema(String name, JsonNode payload) {
        var root = mapper.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("$ref", "#/components/schemas/" + name);
        root.set("components", document.get("components"));
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
        assertThat(schema.validate(payload)).as(name).isEmpty();
    }

    static Stream<Arguments> requests() {
        return Stream.of(
                Arguments.of("RequestCodeRq", "{\"email\":\"user@example.com\"}", true),
                Arguments.of("RequestCodeRq", "{}", false),
                Arguments.of(
                        "VerifyCodeRq",
                        "{\"challengeId\":\"11111111-1111-4111-8111-111111111111\",\"code\":\"012345\"}",
                        true),
                Arguments.of(
                        "VerifyCodeRq",
                        "{\"challengeId\":\"11111111-1111-4111-8111-111111111111\",\"code\":123456}",
                        false),
                Arguments.of(
                        "VerifyCodeRq",
                        "{\"challengeId\":\"11111111-1111-4111-8111-111111111111\",\"code\":\"12345\"}",
                        false),
                Arguments.of("UpdateProfileRq", "{\"displayName\":\"Иван\",\"version\":0}", true),
                Arguments.of("UpdateProfileRq", "{\"displayName\":\"Иван\"}", false),
                Arguments.of(
                        "UpdateProfileRq", "{\"displayName\":\"Иван\",\"version\":0.5}", false),
                Arguments.of("UpdateProfileRq", "{\"displayName\":\"Иван\",\"version\":-1}", false),
                Arguments.of(
                        "UpdateProfileRq",
                        "{\"displayName\":\"Иван\",\"version\":0,\"userId\":\"other\"}",
                        false));
    }

    @ParameterizedTest
    @MethodSource("requests")
    void schemasAcceptFrontendPayloadsAndRejectInvalidOnes(String name, String json, boolean valid)
            throws Exception {
        var root = mapper.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("$ref", "#/components/schemas/" + name);
        root.set("components", document.get("components"));
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
        assertThat(schema.validate(mapper.readTree(json)).isEmpty()).isEqualTo(valid);
    }
}
