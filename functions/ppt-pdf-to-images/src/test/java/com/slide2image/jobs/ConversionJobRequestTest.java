package com.slide2image.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.slide2image.conversion.ConversionException;
import com.slide2image.conversion.ConversionOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConversionJobRequestTest {
    private static final String ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String REQUEST = """
            {"version":1,"jobId":"550E8400-E29B-41D4-A716-446655440000",
             "input":{"container":"incoming","blobName":"顧客/資料.pptx"},
             "output":{"container":"converted","prefix":" reports/2026/// "}}
            """;

    @Test
    void minimalRequestDerivesFilenameAndNormalizesDefaults() {
        ConversionJobRequest request = ConversionJobRequest.parse(REQUEST);
        assertEquals(ID, request.jobId());
        assertEquals("顧客/資料.pptx", request.input().blobName());
        assertEquals("default", request.input().storage());
        assertEquals("default", request.output().storage());
        assertEquals("zip", request.output().mode());
        assertEquals("資料.pptx", request.filename());
        assertEquals("reports/2026", request.output().prefix());
        assertEquals(new ConversionOptions(null, "png", null), request.options());
        assertEquals(request, request.normalized());
        assertEquals(request, ConversionJobRequest.parse(request.toJson()));
    }

    @Test
    void imageModeIsExplicitAndDefaultModeRetainsLegacyRequestEquality() throws Exception {
        ConversionJobRequest legacy = ConversionJobRequest.parse(REQUEST);
        for (String mode : new String[] {"\"zip\"", "null"}) {
            assertEquals(legacy, ConversionJobRequest.parse(REQUEST.replace("\"container\":\"converted\"",
                    "\"mode\":" + mode + ",\"container\":\"converted\"")));
        }
        ConversionJobRequest images = ConversionJobRequest.parse(REQUEST.replace("\"container\":\"converted\"",
                "\"mode\":\"images\",\"container\":\"converted\""));
        assertEquals("images", images.output().mode());
        assertNotEquals(legacy, images);
        assertEquals(images, ConversionJobRequest.parse(images.toJson()));
        // Persisted pre-feature requests also use the canonical default when read through Jackson.
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String oldState = mapper.writeValueAsString(legacy).replace(",\"mode\":\"zip\"", "");
        assertEquals(legacy, mapper.readValue(oldState, ConversionJobRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"tar\"", "\"\"", "\"IMAGES\"", "1", "true", "[]", "{}"})
    void rejectsInvalidOutputModes(String mode) {
        invalid(REQUEST.replace("\"container\":\"converted\"", "\"mode\":" + mode + ",\"container\":\"converted\""));
    }

    @Test
    void acceptsRegisteredAliasNamesButNeverUrlsOrCredentials() {
        ConversionJobRequest request = ConversionJobRequest.parse(REQUEST
                .replace("\"container\":\"incoming\"", "\"storage\":\"source\",\"container\":\"incoming\"")
                .replace("\"container\":\"converted\"", "\"storage\":\"archive\",\"container\":\"converted\""));
        assertEquals("source", request.input().storage());
        assertEquals("archive", request.output().storage());
        assertEquals(request, ConversionJobRequest.parse(request.toJson()));
        for (String invalidAlias : new String[] {"", "SOURCE", "unknown-name", "0account", "https://account.blob.core.windows.net", "a".repeat(33)}) {
            invalid(REQUEST.replace("\"container\":\"incoming\"", "\"storage\":\"" + invalidAlias + "\",\"container\":\"incoming\""));
        }
    }

    @Test
    void explicitOptionsAndDisplayNameAreNormalized() {
        ConversionJobRequest request = new ConversionJobRequest(1, ID,
                new ConversionJobRequest.BlobSource("incoming", "original"),
                new ConversionJobRequest.BlobOutput("converted", null),
                "C:\\temp\\日本語\n資料.pdf", new ConversionOptions(1200, " JPEG ", 2)).normalized();
        assertEquals("日本語_資料.pdf", request.filename());
        assertEquals("", request.output().prefix());
        assertEquals(new ConversionOptions(1200, "jpeg", 2), request.options());
        assertEquals(request, ConversionJobRequest.parse(request.toJson()));
    }

    @Test
    void omittedPrefixAndFormatAndNullDimensionsUseDefaults() {
        String payload = REQUEST.replace(",\"prefix\":\" reports/2026/// \"", "")
                .replace("}\n", ",\"options\":{\"width\":null,\"page\":null}}\n");
        ConversionJobRequest request = ConversionJobRequest.parse(payload);
        assertEquals("", request.output().prefix());
        assertEquals(new ConversionOptions(null, "png", null), request.options());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "\"job-id\"", "{broken", "true"})
    void rejectsNonRequestJson(String payload) {
        invalid(payload);
    }

    @Test
    void rejectsDuplicateKeysTrailingTokensAndUnknownFields() {
        invalid(REQUEST + " {}");
        invalid(REQUEST.replace("\"version\":1", "\"version\":1,\"version\":1"));
        invalid(REQUEST.replace("\"container\":\"incoming\"", "\"container\":\"incoming\",\"container\":\"other\""));
        invalid(REQUEST.replace("\"version\":1", "\"version\":1,\"connectionString\":\"secret\""));
        invalid(REQUEST.replace("\"blobName\":", "\"url\":\"https://example.test\",\"blobName\":"));
        invalid(REQUEST.replace("}\n", ",\"options\":{\"quality\":90}}\n"));
    }

    @Test
    void malformedJsonDoesNotExposeProducerContentThroughAnExceptionCause() {
        ConversionException failure = assertThrows(ConversionException.class,
                () -> ConversionJobRequest.parse("{\"connectionString\":\"producer-secret\"BROKEN}"));
        assertEquals("The queue message is not valid JSON.", failure.getMessage());
        assertNull(failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2", "null", "true", "\"1\"", "1.0", "2147483648"})
    void rejectsInvalidVersionWithoutCoercion(String version) {
        invalid(REQUEST.replace("\"version\":1", "\"version\":" + version));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "1.0", "\"100\"", "true", "[]", "2147483648"})
    void rejectsInvalidNumericOptionsWithoutCoercion(String value) {
        for (String option : new String[] {"width", "page"}) {
            invalid(REQUEST.replace("}\n", ",\"options\":{\"" + option + "\":" + value + "}}\n"));
        }
    }

    @Test
    void rejectsInvalidFieldTypesAndOptions() {
        invalid(REQUEST.replace("\"incoming\"", "123"));
        invalid(REQUEST.replace("\"顧客/資料.pptx\"", "false"));
        invalid(REQUEST.replace("\" reports/2026/// \"", "[]"));
        invalid(REQUEST.replace("}\n", ",\"filename\":12}\n"));
        invalid(REQUEST.replace("}\n", ",\"options\":null}\n"));
        invalid(REQUEST.replace("}\n", ",\"options\":{\"format\":1}}\n"));
        invalid(REQUEST.replace("}\n", ",\"options\":{\"format\":\"gif\"}}\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "Uppercase", "-incoming", "incoming-", "in--coming", "in_coming", "$root"})
    void rejectsInvalidContainers(String container) {
        invalid(REQUEST.replace("incoming", container));
    }

    @Test
    void validatesLiteralBlobPathsWithoutDecodingOrRewritingThem() {
        String literal = "顧客/quarter%2F1/report.pptx";
        assertEquals(literal, ConversionJobRequest.parse(REQUEST.replace("顧客/資料.pptx", literal)).input().blobName());
        invalid(REQUEST.replace("顧客/資料.pptx", "顧客/../資料.pptx"));
        invalid(REQUEST.replace("顧客/資料.pptx", "顧客/./資料.pptx"));
        invalid(REQUEST.replace("顧客/資料.pptx", "顧客/\\u0000資料.pptx"));
        invalid(REQUEST.replace("顧客/資料.pptx", "a".repeat(1025)));
        invalid(REQUEST.replace(" reports/2026/// ", "a".repeat(513)));
        invalid(REQUEST.replace(" reports/2026/// ", "reports/../results"));
        invalid(REQUEST.replace(" reports/2026/// ", "\\nreports"));
        invalid(REQUEST.replace("incoming", "a".repeat(64)));
    }

    @Test
    void validatesConstructedRecordsAndBoundsDisplayNames() {
        ConversionJobRequest base = ConversionJobRequest.parse(REQUEST);
        assertThrows(ConversionException.class, () -> new ConversionJobRequest(2, ID, base.input(), base.output(), null, null).normalized());
        assertThrows(ConversionException.class, () -> new ConversionJobRequest(1, ID, null, base.output(), null, null).normalized());
        assertThrows(ConversionException.class, () -> new ConversionJobRequest(1, ID, base.input(), base.output(), "a".repeat(256), null).normalized());
        assertThrows(ConversionException.class, () -> new ConversionJobRequest(1, ID, base.input(), base.output(), "..", null).normalized());
        assertThrows(ConversionException.class, () -> new ConversionJobRequest(1, ID, base.input(), base.output(), null, new ConversionOptions(0, "png", null)).normalized());
    }

    @Test
    void enforcesUtf8MessageLimitRatherThanCharacterCount() {
        invalid(" ".repeat(ConversionJobRequest.MAX_MESSAGE_BYTES) + REQUEST);
        invalid(REQUEST.replace("顧客/資料.pptx", "資".repeat(17_000)));
    }

    @Test
    void rejectsShortenedAndInvalidJobIdsWithStableError() {
        for (String id : new String[] {"1-1-1-1-1", "not-a-uuid", " " + ID, ID + " ", ""}) {
            ConversionException failure = assertThrows(ConversionException.class,
                    () -> ConversionJobRequest.normalizeJobId(id));
            assertEquals("INVALID_JOB_ID", failure.code());
            assertEquals(400, failure.statusCode());
        }
    }

    private static void invalid(String payload) {
        ConversionException failure = assertThrows(ConversionException.class, () -> ConversionJobRequest.parse(payload));
        assertEquals("INVALID_QUEUE_MESSAGE", failure.code());
        assertEquals(400, failure.statusCode());
    }
}
