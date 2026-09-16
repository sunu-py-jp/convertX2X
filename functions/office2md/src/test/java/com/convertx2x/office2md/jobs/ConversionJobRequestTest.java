package com.convertx2x.office2md.jobs;

import com.convertx2x.office2md.conversion.ConversionException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ConversionJobRequestTest {
    @Test void roundTripsDirectRequestAndUsesIndependentStorageRoles() {
        var request = new ConversionJobRequest(1, UUID.randomUUID().toString(),
                new ConversionJobRequest.BlobSource("source", "incoming", "日本語/台帳.xlsx"),
                new ConversionJobRequest.BlobOutput("destination", "outgoing", "customer/results/"), null).normalized();
        assertEquals("台帳.xlsx", request.filename());
        assertEquals("customer/results", request.output().prefix());
        assertEquals(request, ConversionJobRequest.parse(request.toJson()));
        var profiles = BlobStorageProfiles.from(Map.of("CONVERSION_INPUT_STORAGE_SOURCE", "secret-a",
                "CONVERSION_OUTPUT_STORAGE_DESTINATION", "secret-b"), "secret-main");
        assertEquals("secret-a", profiles.inputConnection("source"));
        assertEquals("UNKNOWN_OUTPUT_STORAGE", assertThrows(ConversionException.class,
                () -> profiles.outputConnection("source")).code());
        assertFalse(profiles.toString().contains("secret"));
    }

    @Test void rejectsUnknownFieldsCoercionDuplicatesTrailingDataAndUnsafePaths() {
        String json = request().toJson();
        for (String invalid : new String[]{
                json.replace("\"version\":1", "\"version\":\"1\""),
                json.replace("\"version\":1", "\"version\":1,\"version\":1"),
                json.substring(0, json.length()-1) + ",\"options\":{}}",
                json.replace("\"prefix\":\"\"", "\"prefix\":\"../other\""),
                json.replace("\"storage\":\"default\"", "\"storage\":\"https://example.test\""),
                json + " {}", "not json", "{}"}) {
            assertThrows(ConversionException.class, () -> ConversionJobRequest.parse(invalid), invalid);
        }
    }

    @Test void versionTwoRoundTripsBoundedMetadataEtagAndRegisteredNotification() {
        var old = request();
        var request = new ConversionJobRequest(2, old.jobId(),
                new ConversionJobRequest.BlobSource("default", "incoming", "source.xlsx", "\"etag-1\""),
                old.output(), old.filename(), Map.of("externalId", "abc", "revision", "7"), new ConversionJobRequest.Notification("events"));
        assertEquals(request.normalized(), ConversionJobRequest.parse(request.toJson()));
        assertFalse(old.toJson().contains("metadata")); assertFalse(old.toJson().contains("expectedETag"));
        assertFalse(old.toJson().contains("notification"));
        assertThrows(ConversionException.class, () -> ConversionJobRequest.parse(request.toJson().replace("\"version\":2", "\"version\":1")));
        assertThrows(ConversionException.class, () -> ConversionJobRequest.parse(request.toJson().replace("\"revision\":\"7\"", "\"revision\":7")));
        assertThrows(ConversionException.class, () -> ConversionJobRequest.parse(request.toJson().replace("\"queue\":\"events\"", "\"queue\":\"https://bad.example\"")));
        assertThrows(ConversionException.class, () -> new ConversionJobRequest(2, old.jobId(), old.input(), old.output(), old.filename(),
                Map.of("large", "x".repeat(513)), null).normalized());
        var excessive = new java.util.HashMap<String, String>(); for (int n = 0; n < 17; n++) excessive.put("k" + n, "v");
        assertThrows(ConversionException.class, () -> new ConversionJobRequest(2, old.jobId(), old.input(), old.output(), old.filename(), excessive, null).normalized());
        var bytes = new java.util.HashMap<String, String>(); for (int n = 0; n < 16; n++) bytes.put("k" + n, "字".repeat(512));
        assertThrows(ConversionException.class, () -> new ConversionJobRequest(2, old.jobId(), old.input(), old.output(), old.filename(), bytes, null).normalized());
    }

    @Test void managedIdentityProfilesAndRetentionAreValidatedWithoutExposingSecrets() {
        var values = Map.of("CONVERSION_STORAGE__blobServiceUri", "https://account.blob.core.windows.net",
                "CONVERSION_STORAGE__queueServiceUri", "https://account.queue.core.windows.net",
                "CONVERSION_INPUT_STORAGE_PRIVATE__blobServiceUri", "https://private.blob.core.windows.net",
                "CONVERSION_CREATE_RESOURCES", "false");
        var config = com.convertx2x.office2md.AppConfig.from(values);
        assertTrue(config.asyncEnabled()); assertFalse(config.integration().createResources());
        assertEquals("https://account.queue.core.windows.net/events", config.integration().storage().queue("events").getQueueUrl());
        assertEquals("https://account.blob.core.windows.net", config.integration().storage().blobs().getAccountUrl());
        assertEquals("https://private.blob.core.windows.net", config.blobStorageProfiles().input("private").blobServiceUri());
        assertThrows(ConversionException.class, () -> config.blobStorageProfiles().output("private"));
        assertFalse(config.toString().contains("account.blob"));
        assertThrows(IllegalArgumentException.class, () -> IntegrationSettings.from(Map.of("CONVERSION_RESULT_RETENTION_DAYS", "5", "CONVERSION_STATE_RETENTION_DAYS", "5")));
        assertThrows(IllegalArgumentException.class, () -> IntegrationSettings.from(Map.of("CONVERSION_STATE_RETENTION_DAYS", "7")));
        assertThrows(IllegalArgumentException.class, () -> IntegrationSettings.from(Map.of("CONVERSION_RESULT_QUEUE_LOOP__queueName", AzureJobService.QUEUE_NAME,
                "CONVERSION_RESULT_QUEUE_LOOP__connectionString", "secret")));
        assertThrows(IllegalArgumentException.class, () -> StorageConnection.from(Map.of("CONVERSION_STORAGE_CONNECTION_STRING", "secret",
                "CONVERSION_STORAGE__blobServiceUri", "https://account.blob.core.windows.net")));
        assertThrows(IllegalArgumentException.class, () -> new StorageConnection(null, null, null, "-".repeat(36)));
        assertThrows(IllegalArgumentException.class, () -> StorageConnection.from(Map.of("CONVERSION_STORAGE__blobServiceUri", "https://host/blob?sig=secret")));
    }

    static ConversionJobRequest request() {
        return new ConversionJobRequest(1, UUID.randomUUID().toString(),
                new ConversionJobRequest.BlobSource("incoming", "input.xlsx"),
                new ConversionJobRequest.BlobOutput("outgoing", ""), "input.xlsx").normalized();
    }
}
