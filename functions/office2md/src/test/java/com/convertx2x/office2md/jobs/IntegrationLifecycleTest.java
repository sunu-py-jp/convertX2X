package com.convertx2x.office2md.jobs;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.*;
import com.azure.storage.blob.models.*;
import com.azure.storage.queue.*;
import com.convertx2x.office2md.conversion.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CONVERSION_TEST_STORAGE_CONNECTION_STRING", matches = ".+")
class IntegrationLifecycleTest {
    private final String connection = System.getenv("CONVERSION_TEST_STORAGE_CONNECTION_STRING");
    private final ObjectMapper json = new ObjectMapper();
    private BlobContainerClient container(String name) { return new BlobServiceClientBuilder().connectionString(connection).buildClient().getBlobContainerClient(name); }
    private QueueClient queue(String name) { return new QueueClientBuilder().connectionString(connection).queueName(name).messageEncoding(QueueMessageEncoding.BASE64).buildClient(); }
    private Map<String, String> settings(String queue) {
        return Map.of("CONVERSION_STORAGE_CONNECTION_STRING", connection, "CONVERSION_CREATE_RESOURCES", "false",
                "CONVERSION_RESULT_QUEUE_EVENTS__queueName", queue, "CONVERSION_RESULT_QUEUE_EVENTS__connectionString", connection,
                "CONVERSION_RESULT_RETENTION_DAYS", "1", "CONVERSION_STATE_RETENTION_DAYS", "2");
    }
    private AzureJobStore store(Map<String, String> values) {
        return new AzureJobStore(IntegrationSettings.from(values), ConversionLimits.defaults(), BlobStorageProfiles.from(values, connection));
    }
    private void initialize() { container(AzureJobService.CONTAINER_NAME).createIfNotExists(); queue(AzureJobService.QUEUE_NAME).createIfNotExists(); }
    private void deleteJob(String id) {
        var control = container(AzureJobService.CONTAINER_NAME);
        for (var blob : control.listBlobs(new ListBlobsOptions().setPrefix(id + "/"), Duration.ofSeconds(10))) control.getBlobClient(blob.getName()).deleteIfExists();
    }

    @Test void requestedInputVersionHashesDurableNotificationAndRetentionWorkAgainstStorage() throws Exception {
        initialize(); String id = UUID.randomUUID().toString(), eventsName = "events-" + id;
        var input = container("source-" + id); var output = container("result-" + id); var events = queue(eventsName);
        input.create(); output.create();
        AzureJobStore store = store(settings(eventsName));
        try {
            var source = input.getBlobClient("original.xlsx"); source.upload(BinaryData.fromString("original"));
            String expected = source.getProperties().getETag();
            var request = new ConversionJobRequest(2, id, new ConversionJobRequest.BlobSource("default", input.getBlobContainerName(), "original.xlsx", expected),
                    new ConversionJobRequest.BlobOutput(output.getBlobContainerName(), "prefix"), "original.xlsx", Map.of("revision", "v7"), new ConversionJobRequest.Notification("events"));
            assertEquals("INPUT_VERSION_MISMATCH", assertThrows(ConversionException.class, () -> store.readInputVersioned(
                    new ConversionJobRequest.BlobSource("default", input.getBlobContainerName(), "original.xlsx", "\"old\""))).code());
            assertEquals(expected, store.readInputVersioned(request.input()).eTag());
            AtomicInteger renders = new AtomicInteger();
            AzureJobService service = new AzureJobService(store, (bytes, name) -> { renders.incrementAndGet(); return AzureJobServiceTest.result(); },
                    (bytes, name) -> {}, Clock.systemUTC());
            // The notification queue intentionally does not exist. Conversion still commits and leaves a durable pending outbox.
            service.process(request.toJson()); var record = store.find(id).orElseThrow();
            assertEquals("succeeded", record.job().status()); assertTrue(record.pendingNotification()); assertEquals(expected, record.inputETag());
            var manifest = record.result().manifest(); assertNotNull(manifest);
            byte[] manifestBytes = output.getBlobClient(manifest.blobName()).downloadContent().toBytes();
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)), manifest.sha256());
            var document = json.readTree(manifestBytes); assertEquals(expected, document.path("input").path("eTag").asText());
            assertEquals("v7", document.path("metadata").path("revision").asText());
            for (var artifact : record.result().artifacts()) {
                byte[] bytes = output.getBlobClient(artifact.blobName()).downloadContent().toBytes();
                assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), artifact.sha256());
            }
            // Pending notification prevents cleanup even when all retention windows have elapsed.
            try (var lock = store.lock(id)) { store.cleanup(record, lock, Instant.now().plus(Duration.ofDays(5))); }
            assertTrue(output.getBlobClient(manifest.blobName()).exists());
            events.create();
            for (int n = 0; n < 20 && store.find(id).orElseThrow().pendingNotification(); n++) service.maintenance();
            record = store.find(id).orElseThrow(); assertNotNull(record.notificationSentAt());
            var message = events.receiveMessage(); assertNotNull(message);
            var event = json.readTree(message.getMessageText());
            assertEquals(id + ":succeeded", event.path("eventId").asText());
            assertEquals(expected, event.path("input").path("eTag").asText());
            assertEquals(manifest.sha256(), event.path("result").path("manifest").path("sha256").asText());
            events.deleteMessage(message.getMessageId(), message.getPopReceipt());
            service.process(request.toJson()); assertEquals(1, renders.get()); assertNull(events.receiveMessage());
            // Delivery after the original completion TTL grants a full retention interval from acknowledged delivery.
            JobStatus old = record.job();
            record = new JobRecord(new JobStatus(old.id(), old.status(), old.filename(), old.createdAt(),
                    Instant.now().minus(Duration.ofDays(5)).toString(), old.sectionCount(), old.warningCount(), null, null),
                    record.result(), record.request(), record.inputETag(), Instant.now().toString(), null);
            try (var lock = store.lock(id)) { lock.update(record); store.cleanup(record, lock, Instant.now()); }
            assertTrue(output.getBlobClient(manifest.blobName()).exists(), "Delayed notification starts a new retention interval");
            // Even if a caller keeps our metadata, an altered ETag protects its replacement from cleanup.
            var modified = record.result().artifacts().getFirst(); var replaced = output.getBlobClient(modified.blobName());
            Map<String, String> marker = replaced.getProperties().getMetadata();
            replaced.upload(BinaryData.fromString("caller replacement"), true); replaced.setMetadata(marker);
            try (var lock = store.lock(id)) { store.cleanup(record, lock, Instant.now().plus(Duration.ofHours(30))); }
            assertTrue(source.exists(), "Caller original is never owned"); assertTrue(replaced.exists(), "Caller replacement must remain");
            assertFalse(output.getBlobClient(manifest.blobName()).exists());
            assertEquals("JOB_RESULT_EXPIRED", assertThrows(ConversionException.class, () -> service.download(id, "document.md")).code());
            assertTrue(store.find(id).isPresent(), "Dedupe state outlives artifacts");
            try (var lock = store.lock(id)) { store.cleanup(store.find(id).orElseThrow(), lock, Instant.now().plus(Duration.ofDays(3))); }
            assertTrue(store.find(id).isEmpty());
            var index = container(AzureJobService.CONTAINER_NAME).getBlobClient(".maintenance/jobs/" + id + ".json");
            assertTrue(index.exists(), "Index removal must not race a new job reusing an expired ID");
            String previousIndexETag = index.getProperties().getETag();
            store.ensure(new JobRecord(new JobStatus(id, "queued", request.filename(), Instant.now().toString(), Instant.now().toString(), null, null, null, null), null, request));
            assertNotEquals(previousIndexETag, index.getProperties().getETag(), "Ensure refreshes the index before creating replacement state");
        } finally { input.deleteIfExists(); output.deleteIfExists(); events.deleteIfExists(); deleteJob(id); }
    }

    @Test void replayedLegacyStateRemainsDownloadableAndIsNeverAutomaticallyReaped() throws Exception {
        initialize(); String id = UUID.randomUUID().toString(); var output = container("legacy-" + id); output.create();
        var store = store(settings("unused-" + id)); var control = container(AzureJobService.CONTAINER_NAME);
        var request = new ConversionJobRequest(1, id, new ConversionJobRequest.BlobSource("original", "caller.xlsx"),
                new ConversionJobRequest.BlobOutput(output.getBlobContainerName(), ""), "caller.xlsx");
        try {
            var file = output.getBlobClient("legacy/document.md"); file.upload(BinaryData.fromString("# Legacy"));
            var location = new JobRecord.ResultLocation("default", output.getBlobContainerName(), List.of(
                    new JobRecord.Artifact("document.md", file.getBlobName(), "text/markdown", 8, file.getProperties().getETag())), 1, 0);
            var status = new JobStatus(id, "succeeded", "caller.xlsx", Instant.now().toString(), Instant.now().toString(), 1, 0, null, null);
            var state = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(new JobRecord(status, location, request));
            state.remove("artifactTrackingVersion");
            control.getBlobClient(id + "/status.json").upload(BinaryData.fromBytes(json.writeValueAsBytes(state)));
            var service = new AzureJobService(store, (bytes, name) -> { throw new AssertionError("Legacy terminal replay must not render"); },
                    (bytes, name) -> {}, Clock.systemUTC());
            service.process(request.toJson()); // Adds discovery for an older status, but does not grant ownership.
            var legacy = store.find(id).orElseThrow(); assertEquals(0, legacy.artifactTrackingVersion());
            try (var lock = store.lock(id)) { store.cleanup(legacy, lock, Instant.now().plus(Duration.ofDays(10))); }
            assertTrue(store.find(id).isPresent()); assertNull(store.find(id).orElseThrow().artifactsExpiredAt());
            assertEquals("# Legacy", new String(service.download(id, "document.md").bytes(), StandardCharsets.UTF_8));
            assertTrue(file.exists());
        } finally { output.deleteIfExists(); deleteJob(id); }
    }

    @Test void cleanupResumesItsExactLedgerAfterOneHundredDeletesWithoutDroppingState() throws Exception {
        initialize(); String id = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        var output = container("cleanup-" + id); output.create(); var store = store(settings("unused-" + id));
        var control = container(AzureJobService.CONTAINER_NAME);
        var request = new ConversionJobRequest(1, id, new ConversionJobRequest.BlobSource("original", "caller.xlsx"),
                new ConversionJobRequest.BlobOutput(output.getBlobContainerName(), ""), "caller.xlsx");
        var status = new JobStatus(id, "failed", "caller.xlsx", Instant.now().toString(), Instant.now().toString(), null, null, "PROCESSING_FAILED", "Failed");
        var record = new JobRecord(status, null, request); store.ensure(record);
        List<String> names = new ArrayList<>(); Map<String, String> eTags = new HashMap<>();
        try {
            for (int n = 0; n < 101; n++) {
                String name = id + "/results/" + token + "/images/" + n + ".png"; names.add(name);
                var blob = output.getBlobClient(name); blob.upload(BinaryData.fromString("image"));
                blob.setMetadata(Map.of("convertx2xowner", token)); eTags.put(name, blob.getProperties().getETag());
            }
            var ledger = control.getBlobClient(id + "/ownership/" + token + ".json");
            ledger.upload(BinaryData.fromBytes(json.writeValueAsBytes(new AzureJobStore.Ownership(token, "default", output.getBlobContainerName(), false, names, eTags))));
            try (var lock = store.lock(id)) { store.cleanup(record, lock, Instant.now().plus(Duration.ofDays(3))); }
            assertTrue(store.find(id).isPresent(), "State cannot disappear while cleanup has a remaining batch");
            assertEquals(1, json.readTree(ledger.downloadContent().toBytes()).path("blobNames").size());
            assertTrue(output.getBlobClient(names.getLast()).exists());
            try (var lock = store.lock(id)) { store.cleanup(store.find(id).orElseThrow(), lock, Instant.now().plus(Duration.ofDays(3))); }
            assertTrue(store.find(id).isEmpty()); assertFalse(ledger.exists()); assertFalse(output.getBlobClient(names.getLast()).exists());
        } finally { output.deleteIfExists(); deleteJob(id); }
    }

    @Test void preprovisionedModeDoesNotCreateMissingOutputContainerAndAbandonedSubmissionExpires() {
        initialize(); String id = UUID.randomUUID().toString();
        var output = container("absent-" + id); var store = store(settings("unused-" + id));
        var request = new ConversionJobRequest(1, id, new ConversionJobRequest.BlobSource(AzureJobService.CONTAINER_NAME, id + "/input"),
                new ConversionJobRequest.BlobOutput(output.getBlobContainerName(), ""), "source.xlsx");
        var status = new JobStatus(id, "queued", "source.xlsx", Instant.now().toString(), Instant.now().toString(), null, null, null, null);
        var pending = new JobRecord(status, null, request, null, null, null, true);
        try {
            store.ensure(pending);
            try (var lock = store.lock(id)) { store.create(pending, new byte[]{1,2,3}); }
            assertTrue(container(AzureJobService.CONTAINER_NAME).getBlobClient(id + "/input").exists());
            try (var result = AzureJobServiceTest.result()) { assertThrows(BlobStorageException.class, () -> store.writeResult(request, result)); }
            assertFalse(output.exists());
            try (var lock = store.lock(id)) { store.cleanup(pending, lock, Instant.now().plus(Duration.ofHours(30))); }
            var failed = store.find(id).orElseThrow();
            assertEquals("SUBMISSION_EXPIRED", failed.job().errorCode());
            assertFalse(container(AzureJobService.CONTAINER_NAME).getBlobClient(id + "/input").exists());
        } finally { output.deleteIfExists(); deleteJob(id); }
    }
}
