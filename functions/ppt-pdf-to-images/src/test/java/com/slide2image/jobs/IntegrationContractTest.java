package com.slide2image.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.slide2image.AppConfig;
import com.slide2image.conversion.ConversionException;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IntegrationContractTest {
    private ConversionJobRequest request() {
        return new ConversionJobRequest(2, UUID.randomUUID().toString(),
                new ConversionJobRequest.BlobSource("source", "source-files", "doc.pdf", "\"etag-v1\""),
                new ConversionJobRequest.BlobOutput("archive", "result-files", "", "images", "page-number"),
                null, null, Map.of("revision", "42"), new ConversionJobRequest.Notification("events")).normalized();
    }
    @Test void v2RoundTripsAndLegacyWireRejectsEveryAddedField() {
        ConversionJobRequest request = request();
        assertEquals(request, ConversionJobRequest.parse(request.toJson()));
        assertThrows(ConversionException.class, () -> ConversionJobRequest.parse(request.toJson().replace("\"version\":2", "\"version\":1")));
        ConversionJobRequest legacy = new ConversionJobRequest(1, request.jobId(),
                new ConversionJobRequest.BlobSource("source-files", "doc.pdf"),
                new ConversionJobRequest.BlobOutput("result-files", ""), null, null).normalized();
        assertFalse(legacy.toJson().contains("metadata"));
        assertFalse(legacy.toJson().contains("expectedETag"));
        assertFalse(legacy.toJson().contains("notification"));
        assertFalse(legacy.toJson().contains("naming"));
        assertEquals(legacy, ConversionJobRequest.parse(legacy.toJson()));
        for (String field : new String[]{"metadata", "notification"})
            assertThrows(ConversionException.class, () -> ConversionJobRequest.parse(legacy.toJson().replace("\"version\":1", "\"version\":1,\"" + field + "\":{}")));
    }
    @Test void boundsMetadataAndForbidsArbitraryNotificationTargets() {
        String json = request().toJson();
        for (String value : new String[]{"null", "42", "[1]", "{\"nested\":{}}", "{\"key\":42}"})
            assertThrows(ConversionException.class, () -> ConversionJobRequest.parse(json.replace("{\"revision\":\"42\"}", value)));
        assertThrows(ConversionException.class, () -> ConversionJobRequest.parse(json.replace("\"events\"", "\"https://example.org/q\"")));
        ConversionJobRequest original = request();
        assertThrows(ConversionException.class, () -> new ConversionJobRequest(2, original.jobId(),
                new ConversionJobRequest.BlobSource("source", "source-files", "doc.pdf", "*"), original.output(), null, null).normalized());
        Map<String,String> tooMany = new HashMap<>(); for (int i = 0; i < 17; i++) tooMany.put("key"+i, "v");
        ConversionJobRequest base = request();
        assertThrows(ConversionException.class, () -> new ConversionJobRequest(2, base.jobId(), base.input(), base.output(), null, null, tooMany, null).normalized());
        assertThrows(ConversionException.class, () -> new ConversionJobRequest(2, base.jobId(), base.input(), base.output(), null, null, Map.of("k", "a".repeat(513)), null).normalized());
    }
    @Test void managedIdentityEnablesAsyncAndUsesSeparateRoleAliases() {
        Map<String,String> settings = Map.of(
                "CONVERSION_STORAGE__blobServiceUri", "https://control.blob.core.windows.net",
                "CONVERSION_STORAGE__queueServiceUri", "https://control.queue.core.windows.net",
                "CONVERSION_INPUT_STORAGE_SOURCE__blobServiceUri", "https://source.blob.core.windows.net",
                "CONVERSION_OUTPUT_STORAGE_ARCHIVE__blobServiceUri", "https://archive.blob.core.windows.net",
                "CONVERSION_CREATE_RESOURCES", "false");
        AppConfig config = AppConfig.from(settings);
        assertTrue(config.asyncEnabled()); assertFalse(config.integration().createResources());
        assertEquals("https://source.blob.core.windows.net", config.blobStorageProfiles().input("source").blobServiceUri());
        assertThrows(ConversionException.class, () -> config.blobStorageProfiles().output("source"));
        assertDoesNotThrow(() -> config.integration().control().blobs());
        assertDoesNotThrow(() -> config.integration().control().queue("conversion-jobs"));
        assertFalse(config.toString().contains("https://"));
    }
    @Test void refusesNotificationLoopsIntoTheSameStorageWorkOrPoisonQueue() {
        for (String name : new String[]{"conversion-jobs", "conversion-jobs-poison"}) {
            Map<String,String> same = Map.of("CONVERSION_STORAGE_CONNECTION_STRING", "UseDevelopmentStorage=true",
                    "CONVERSION_RESULT_QUEUE_EVENTS__queueName", name,
                    "CONVERSION_RESULT_QUEUE_EVENTS__connectionString", "UseDevelopmentStorage=true");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> IntegrationSettings.from(same));
            assertFalse(failure.getMessage().contains("devstoreaccount1"));
            Map<String,String> identity = Map.of("CONVERSION_STORAGE__blobServiceUri", "https://control.blob.core.windows.net",
                    "CONVERSION_STORAGE__queueServiceUri", "https://control.queue.core.windows.net",
                    "CONVERSION_RESULT_QUEUE_EVENTS__queueName", name,
                    "CONVERSION_RESULT_QUEUE_EVENTS__queueServiceUri", "https://control.queue.core.windows.net:443/",
                    "CONVERSION_RESULT_QUEUE_EVENTS__clientId", "11111111-1111-1111-1111-111111111111");
            assertThrows(IllegalArgumentException.class, () -> IntegrationSettings.from(identity));
            Map<String,String> other = new HashMap<>(identity);
            other.put("CONVERSION_RESULT_QUEUE_EVENTS__queueServiceUri", "https://external.queue.core.windows.net");
            assertDoesNotThrow(() -> IntegrationSettings.from(other));
        }
    }

    @Test void configurationRejectsIncompleteIdentityUnsafeUrisAndUnsafeRetention() {
        assertThrows(IllegalArgumentException.class, () -> AppConfig.from(Map.of("CONVERSION_STORAGE__blobServiceUri", "https://a.blob.core.windows.net")));
        assertThrows(IllegalArgumentException.class, () -> StorageConnection.from(Map.of("X", "UseDevelopmentStorage=true", "X__blobServiceUri", "https://a.blob.core.windows.net"), "X", "X"));
        assertThrows(IllegalArgumentException.class, () -> StorageConnection.from(Map.of("X__clientId", "-".repeat(36)), "X", "X"));
        for (String url : new String[]{"http://a.blob.core.windows.net", "https://a.blob.core.windows.net?sig=secret", "https://user:pass@a.blob.core.windows.net", "https://a.blob.core.windows.net/container"})
            assertThrows(IllegalArgumentException.class, () -> StorageConnection.from(Map.of("X__blobServiceUri", url), "X", "X"));
        assertThrows(IllegalArgumentException.class, () -> IntegrationSettings.from(Map.of("CONVERSION_STATE_RETENTION_DAYS", "2")));
        assertThrows(IllegalArgumentException.class, () -> IntegrationSettings.from(Map.of("CONVERSION_RESULT_RETENTION_DAYS", "2", "CONVERSION_STATE_RETENTION_DAYS", "2")));
        assertEquals(3, IntegrationSettings.from(Map.of("CONVERSION_RESULT_RETENTION_DAYS", "2", "CONVERSION_STATE_RETENTION_DAYS", "3")).stateRetentionDays());
    }
}
