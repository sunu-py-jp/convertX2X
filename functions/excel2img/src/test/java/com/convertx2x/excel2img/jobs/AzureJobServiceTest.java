package com.convertx2x.excel2img.jobs;

import com.convertx2x.excel2img.conversion.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AzureJobServiceTest {
    @Test void directQueueCreatesStatePublishesAllArtifactsThenIgnoresRedelivery() {
        MemoryStore store = new MemoryStore();
        AtomicInteger conversions = new AtomicInteger();
        AzureJobService service = service(store, (bytes, name, options) -> { conversions.incrementAndGet(); return result(); });
        var request = ConversionJobRequestTest.request();
        service.process(request.toJson());
        JobStatus status = service.find(request.jobId()).orElseThrow();
        assertEquals("succeeded", status.status());
        assertEquals(1, status.sectionCount());
        assertEquals(List.of("running", "succeeded"), store.transitions);
        assertEquals(Set.of("pages/page-001.png", "report.json"), store.downloads.keySet());
        assertFalse(Files.exists(store.lastDirectory), "Temporary result must close after upload");
        assertArrayEquals(new byte[]{(byte)137, 80, 78, 71},
                service.download(request.jobId(), "pages/page-001.png").bytes());
        service.process(request.toJson());
        service.poison(request.toJson());
        assertEquals(1, conversions.get());
        assertEquals("succeeded", service.find(request.jobId()).orElseThrow().status());
    }

    @Test void retriesTransientUploadWithoutPublishingPartialResultAndClosesWorkspace() {
        MemoryStore store = new MemoryStore(); store.failUpload = true;
        AzureJobService service = service(store, (bytes, name, options) -> result());
        var request = ConversionJobRequestTest.request();
        assertThrows(IllegalStateException.class, () -> service.process(request.toJson()));
        assertEquals("running", service.find(request.jobId()).orElseThrow().status());
        assertNull(store.records.get(request.jobId()).result());
        assertFalse(Files.exists(store.lastDirectory));
        assertEquals("JOB_NOT_READY", assertThrows(ConversionException.class,
                () -> service.download(request.jobId(), "document.pdf")).code());
        store.failUpload = false;
        service.process(request.toJson());
        assertEquals("succeeded", service.find(request.jobId()).orElseThrow().status());
        assertEquals(2, store.attempts);
    }

    @Test void deterministicFailureAndPoisonAreTerminalAndConflictingIdsCannotOverwrite() {
        MemoryStore store = new MemoryStore();
        AzureJobService service = service(store, (bytes, name, options) -> { throw new ConversionException(422, "INVALID_WORKBOOK", "Invalid workbook"); });
        var request = ConversionJobRequestTest.request();
        service.process(request.toJson());
        assertEquals("INVALID_WORKBOOK", service.find(request.jobId()).orElseThrow().errorCode());
        var conflict = new ConversionJobRequest(1, request.jobId(), new ConversionJobRequest.BlobSource("incoming", "other.xlsx"), request.output(), "other.xlsx");
        assertEquals("JOB_ID_CONFLICT", assertThrows(ConversionException.class, () -> service.process(conflict.toJson())).code());
        service.poison(conflict.toJson());
        assertEquals("INVALID_WORKBOOK", service.find(request.jobId()).orElseThrow().errorCode());
        var poison = ConversionJobRequestTest.request();
        service.poison(poison.toJson());
        assertEquals("PROCESSING_FAILED", service.find(poison.jobId()).orElseThrow().errorCode());
        assertDoesNotThrow(() -> service.poison("invalid json"));
    }

    @Test void allSkippedAsyncFailureRetainsPerRangeReasonsForStatus() {
        MemoryStore store = new MemoryStore();
        var details = Map.<String, Object>of("ranges", List.of(Map.of("sheet", "売上", "range", "A1:B2", "status", "skipped", "reason", "TEXT_TOO_SMALL")));
        AzureJobService service = service(store, (bytes, name, options) -> {
            throw new ConversionException(422, "NO_RENDERABLE_RANGE", "No range can be rendered", details);
        });
        var request = ConversionJobRequestTest.request(); service.process(request.toJson());
        assertEquals(details, service.find(request.jobId()).orElseThrow().errorDetails());
        assertEquals("NO_RENDERABLE_RANGE", service.find(request.jobId()).orElseThrow().errorCode());
    }

    @Test void submitPersistsBeforeEnqueueAndActiveLeasePreventsAnotherWorker() {
        MemoryStore store = new MemoryStore();
        AzureJobService service = service(store, (bytes, name, options) -> result());
        JobStatus status = service.submit(new byte[]{1,2}, "path/入力.xlsx");
        assertEquals("入力.xlsx", status.filename());
        assertEquals(status.id(), ConversionJobRequest.parse(store.enqueued).jobId());
        try (JobStore.JobLock ignored = store.lock(status.id())) {
            assertThrows(IllegalStateException.class, () -> service.process(store.enqueued));
        }
        service.process(store.enqueued);
        assertEquals("succeeded", service.find(status.id()).orElseThrow().status());
    }

    @Test void unknownStorageAliasCreatesAnAddressableFailureWithoutReadingInput() {
        MemoryStore store = new MemoryStore();
        store.validationFailure = new ConversionException(400, "UNKNOWN_INPUT_STORAGE", "The storage alias is not registered.");
        AzureJobService service = service(store, (bytes, name, options) -> { throw new AssertionError("Must not convert"); });
        var request = ConversionJobRequestTest.request();
        service.process(request.toJson());
        assertEquals("failed", service.find(request.jobId()).orElseThrow().status());
        assertEquals("UNKNOWN_INPUT_STORAGE", service.find(request.jobId()).orElseThrow().errorCode());
        assertEquals(0, store.inputReads);
    }

    @Test void resumingLegacyQueuedStateDoesNotInventCompleteArtifactOwnership() {
        MemoryStore store = new MemoryStore(); var request = ConversionJobRequestTest.request();
        var legacy = new JobRecord(new JobStatus(request.jobId(), "queued", request.filename(), "created", "updated", null, null, null, null), null, request).withTrackingVersion(0);
        store.ensure(legacy);
        service(store, (bytes, name, options) -> result()).process(request.toJson());
        assertEquals("succeeded", store.records.get(request.jobId()).job().status());
        assertEquals(0, store.records.get(request.jobId()).artifactTrackingVersion());
    }

    @Test void failedHttpInputOrEnqueueLeavesTerminalOwnedStateForCleanup() {
        for (boolean beforeUpload : List.of(true, false)) {
            MemoryStore store = new MemoryStore(); store.failCreate = beforeUpload; store.failEnqueue = !beforeUpload;
            AzureJobService service = service(store, (bytes, name, options) -> { throw new AssertionError("Must not convert"); });
            assertThrows(IllegalStateException.class, () -> service.submit(new byte[]{1,2}, "input.xlsx"));
            assertEquals(1, store.records.size());
            JobRecord failed = store.records.values().iterator().next();
            assertEquals("failed", failed.job().status()); assertEquals("SUBMISSION_FAILED", failed.job().errorCode());
            assertFalse(failed.submissionPending()); assertFalse(store.held);
        }
    }

    @Test void terminalOutboxRetriesWithoutRenderingAndStableIdSurvivesSendAckCrash() {
        MemoryStore store = new MemoryStore(); AtomicInteger conversions = new AtomicInteger();
        AzureJobService service = service(store, (bytes, name, options) -> { conversions.incrementAndGet(); return result(); });
        var old = ConversionJobRequestTest.request();
        var request = new ConversionJobRequest(2, old.jobId(), old.input(), old.output(), old.filename(), Map.of("revision", "2"), new ConversionJobRequest.Notification("events"));
        store.failNotification = true;
        service.process(request.toJson());
        assertEquals("succeeded", store.records.get(request.jobId()).job().status());
        assertNull(store.records.get(request.jobId()).notificationSentAt());
        assertEquals(1, conversions.get());
        store.failNotification = false; store.failNotificationAck = true;
        service.maintenance(); // Queue accepted, durable ack crashed: retry sends same eventId.
        assertNull(store.records.get(request.jobId()).notificationSentAt());
        store.failNotificationAck = false;
        service.maintenance(); service.process(request.toJson());
        assertNotNull(store.records.get(request.jobId()).notificationSentAt());
        assertEquals(2, store.events.size()); assertEquals(store.events.get(0), store.events.get(1));
        assertEquals(request.jobId() + ":succeeded", store.events.get(0).get("eventId"));
        assertEquals(Map.of("revision", "2"), store.events.get(0).get("metadata"));
        assertEquals(1, conversions.get());
    }

    @Test void requestedVersionMismatchAndPoisonHaveTerminalFailureEvents() {
        MemoryStore store = new MemoryStore(); AzureJobService service = service(store, (bytes, name, options) -> { throw new AssertionError("Must not render"); });
        var old = ConversionJobRequestTest.request();
        var request = new ConversionJobRequest(2, old.jobId(), old.input(), old.output(), old.filename(), Map.of(), new ConversionJobRequest.Notification("events"));
        store.inputFailure = new ConversionException(409, "INPUT_VERSION_MISMATCH", "The input changed.");
        service.process(request.toJson());
        assertEquals("INPUT_VERSION_MISMATCH", store.records.get(request.jobId()).job().errorCode());
        assertEquals(Map.of("code", "INPUT_VERSION_MISMATCH", "retryable", false), store.events.getFirst().get("error"));
        var second = new ConversionJobRequest(2, UUID.randomUUID().toString(), old.input(), old.output(), old.filename(), Map.of(), new ConversionJobRequest.Notification("events"));
        service.poison(second.toJson());
        assertEquals("PROCESSING_FAILED", store.records.get(second.jobId()).job().errorCode());
        assertEquals(2, store.events.size());
    }

    @Test void renderOptionsTravelThroughSubmissionWorkerAndIdempotency() {
        MemoryStore store = new MemoryStore();
        var selected = new RenderOptions("png", "売上", "A1:B2");
        var conversions = new AtomicInteger();
        var service = service(store, (bytes, name, options) -> {
            assertEquals(selected, options); conversions.incrementAndGet(); return result();
        });
        JobStatus job = service.submit(new byte[]{1}, "book.xlsx", selected);
        var request = ConversionJobRequest.parse(store.enqueued);
        assertEquals(selected, request.options());
        service.process(store.enqueued); service.process(store.enqueued);
        assertEquals(1, conversions.get());
        var different = new ConversionJobRequest(request.version(), job.id(), request.input(), request.output(),
                request.filename(), request.metadata(), request.notification(), new RenderOptions("pdf", "売上", "A1:B2"));
        assertEquals("JOB_ID_CONFLICT", assertThrows(ConversionException.class, () -> service.process(different.toJson())).code());
        assertEquals(1, conversions.get()); assertEquals(1, store.inputReads);
    }

    @Test void invalidDirectQueueOptionsNeverCreateStateAcquireLeaseOrDownloadInput() {
        MemoryStore store = new MemoryStore();
        var service = service(store, (bytes, name, options) -> { throw new AssertionError("Must not render"); });
        String valid = ConversionJobRequestTest.request().toJson();
        for (String invalid : List.of(valid.replace("\"format\":\"png\"", "\"format\":\"svg\""),
                valid.replace("\"format\":\"png\"", "\"format\":\"png\",\"unexpected\":\"value\""),
                valid.replace("\"range\":null", "\"range\":\"A1:B2\""))) {
            assertThrows(ConversionException.class, () -> service.process(invalid));
            assertTrue(store.records.isEmpty()); assertFalse(store.held); assertEquals(0, store.inputReads);
        }
    }

    @Test void artifactPathsAreAnExactAllowlist() {
        for (String valid : List.of("report.json", "document.pdf", "pages/page-001.png", "pages/page-1000.png"))
            assertDoesNotThrow(() -> AzureJobStore.validateArtifact(valid));
        for (String invalid : List.of("../report.json", "pages/../report.json", "pages/page-001.png/extra", "input", "manifest.json", "pages/page-1.png", "https://blob.test/file.png"))
            assertEquals("ARTIFACT_NOT_FOUND", assertThrows(ConversionException.class, () -> AzureJobStore.validateArtifact(invalid)).code());
    }

    @Test void downloadingAllowedButUnownedFileStopsBeforeBlobAccess() {
        var store = new AzureJobStore("UseDevelopmentStorage=true");
        var result = new JobRecord.ResultLocation("default", "outgoing", List.of(
                new JobRecord.Artifact("pages/page-001.png", "completed/pages/page-001.png", "image/png", 4, "etag")), 1, 0);
        assertEquals("ARTIFACT_NOT_FOUND", assertThrows(ConversionException.class,
                () -> store.readResult(result, "pages/page-002.png")).code());
        assertEquals("ARTIFACT_NOT_FOUND", assertThrows(ConversionException.class,
                () -> store.readResult(result, "document.pdf")).code());
    }

    private static AzureJobService service(MemoryStore store, AzureJobService.Converter converter) {
        return new AzureJobService(store, converter, (bytes, name) -> { }, Clock.systemUTC());
    }

    static ConversionResult result() {
        ConversionWorkspace workspace = new ConversionWorkspace(ConversionLimits.defaults());
        workspace.sectionIncluded();
        workspace.pagesGenerated(1);
        workspace.write("pages/page-001.png", new byte[]{(byte)137, 80, 78, 71});
        workspace.write("report.json", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ConversionResult(workspace);
    }

    private static class MemoryStore implements JobStore {
        final Map<String, JobRecord> records = new HashMap<>();
        final Map<String, JobDownload> downloads = new HashMap<>();
        final List<String> transitions = new ArrayList<>();
        boolean held, failUpload, failCreate, failEnqueue; int attempts, inputReads; Path lastDirectory; String enqueued;
        RuntimeException validationFailure, inputFailure;
        boolean failNotification, failNotificationAck;
        final List<Map<String, Object>> events = new ArrayList<>();
        public void notifyResult(JobRecord record) {
            if (failNotification) throw new IllegalStateException("temporary queue outage");
            events.add(AzureJobStore.notificationEvent(record));
        }
        public void maintenance(java.util.function.Consumer<String> action) { new ArrayList<>(records.keySet()).forEach(action); }

        public void create(JobRecord record, byte[] input) { if (failCreate) throw new IllegalStateException("upload failed"); ensure(record); }
        public void ensure(JobRecord record) { records.putIfAbsent(record.job().id(), record); }
        public void enqueue(String message) {
            if (failEnqueue) throw new IllegalStateException("queue unavailable");
            assertTrue(records.containsKey(ConversionJobRequest.parse(message).jobId())); enqueued = message;
        }
        public Optional<JobRecord> find(String id) { return Optional.ofNullable(records.get(id)); }
        public void validateLocations(ConversionJobRequest request) {
            if (validationFailure != null) throw validationFailure;
        }
        public byte[] readInput(ConversionJobRequest.BlobSource source) { if (inputFailure != null) throw inputFailure; inputReads++; return new byte[]{1,2}; }
        public JobRecord.ResultLocation writeResult(ConversionJobRequest request, ConversionResult result) {
            assertEquals("running", records.get(request.jobId()).job().status());
            attempts++; lastDirectory = result.directory();
            if (failUpload) throw new IllegalStateException("Transient Storage failure");
            try {
                for (var file : result.files().entrySet()) downloads.put(file.getKey(),
                        new JobDownload(Files.readAllBytes(file.getValue()), "application/octet-stream", file.getKey()));
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            return new JobRecord.ResultLocation("default", "outgoing", List.of(), result.sectionCount(), result.warningCount());
        }
        public JobDownload readResult(JobRecord.ResultLocation result, String artifact) { return downloads.get(artifact); }
        public JobDownload readArchive(JobRecord.ResultLocation result) { return new JobDownload(new byte[]{1}, "application/zip", "document.zip"); }
        public JobLock lock(String id) {
            if (held) throw new IllegalStateException("Lease held");
            held = true;
            return new JobLock() {
                public void update(JobRecord record) { assertTrue(held); if (failNotificationAck && record.notificationSentAt() != null) throw new IllegalStateException("ack write failed"); records.put(id, record); transitions.add(record.job().status()); }
                public void close() { held = false; }
            };
        }
    }
}
