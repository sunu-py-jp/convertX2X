package com.convertx2x.excel2md.jobs;

import com.convertx2x.excel2md.conversion.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
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
        AzureJobService service = service(store, (bytes, name) -> { conversions.incrementAndGet(); return result(); });
        var request = ConversionJobRequestTest.request();
        service.process(request.toJson());
        JobStatus status = service.find(request.jobId()).orElseThrow();
        assertEquals("succeeded", status.status());
        assertEquals(1, status.sheetCount());
        assertEquals(List.of("running", "succeeded"), store.transitions);
        assertEquals(Set.of("document.md", "report.json", "images/image-0001.png"), store.downloads.keySet());
        assertFalse(Files.exists(store.lastDirectory), "Temporary result must close after upload");
        assertEquals("# 日本語", new String(service.download(request.jobId(), "document.md").bytes(), StandardCharsets.UTF_8));
        service.process(request.toJson());
        service.poison(request.toJson());
        assertEquals(1, conversions.get());
        assertEquals("succeeded", service.find(request.jobId()).orElseThrow().status());
    }

    @Test void retriesTransientUploadWithoutPublishingPartialResultAndClosesWorkspace() {
        MemoryStore store = new MemoryStore(); store.failUpload = true;
        AzureJobService service = service(store, (bytes, name) -> result());
        var request = ConversionJobRequestTest.request();
        assertThrows(IllegalStateException.class, () -> service.process(request.toJson()));
        assertEquals("running", service.find(request.jobId()).orElseThrow().status());
        assertNull(store.records.get(request.jobId()).result());
        assertFalse(Files.exists(store.lastDirectory));
        assertEquals("JOB_NOT_READY", assertThrows(ConversionException.class,
                () -> service.download(request.jobId(), "document.md")).code());
        store.failUpload = false;
        service.process(request.toJson());
        assertEquals("succeeded", service.find(request.jobId()).orElseThrow().status());
        assertEquals(2, store.attempts);
    }

    @Test void deterministicFailureAndPoisonAreTerminalAndConflictingIdsCannotOverwrite() {
        MemoryStore store = new MemoryStore();
        AzureJobService service = service(store, (bytes, name) -> { throw new ConversionException(422, "INVALID_WORKBOOK", "Invalid workbook"); });
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

    @Test void submitPersistsBeforeEnqueueAndActiveLeasePreventsAnotherWorker() {
        MemoryStore store = new MemoryStore();
        AzureJobService service = service(store, (bytes, name) -> result());
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
        AzureJobService service = service(store, (bytes, name) -> { throw new AssertionError("Must not convert"); });
        var request = ConversionJobRequestTest.request();
        service.process(request.toJson());
        assertEquals("failed", service.find(request.jobId()).orElseThrow().status());
        assertEquals("UNKNOWN_INPUT_STORAGE", service.find(request.jobId()).orElseThrow().errorCode());
        assertEquals(0, store.inputReads);
    }

    private static AzureJobService service(MemoryStore store, AzureJobService.Converter converter) {
        return new AzureJobService(store, converter, (bytes, name) -> { }, Clock.systemUTC());
    }

    static ConversionResult result() {
        ConversionWorkspace workspace = new ConversionWorkspace(ConversionLimits.defaults());
        workspace.sheetIncluded();
        workspace.write("document.md", "# 日本語".getBytes(StandardCharsets.UTF_8));
        workspace.write("report.json", "{}".getBytes(StandardCharsets.UTF_8));
        workspace.write("images/image-0001.png", new byte[]{(byte)137,80,78,71});
        return new ConversionResult(workspace);
    }

    private static class MemoryStore implements JobStore {
        final Map<String, JobRecord> records = new HashMap<>();
        final Map<String, JobDownload> downloads = new HashMap<>();
        final List<String> transitions = new ArrayList<>();
        boolean held, failUpload; int attempts, inputReads; Path lastDirectory; String enqueued;
        RuntimeException validationFailure;
        public void create(JobRecord record, byte[] input) { ensure(record); }
        public void ensure(JobRecord record) { records.putIfAbsent(record.job().id(), record); }
        public void enqueue(String message) {
            assertTrue(records.containsKey(ConversionJobRequest.parse(message).jobId())); enqueued = message;
        }
        public Optional<JobRecord> find(String id) { return Optional.ofNullable(records.get(id)); }
        public void validateLocations(ConversionJobRequest request) {
            if (validationFailure != null) throw validationFailure;
        }
        public byte[] readInput(ConversionJobRequest.BlobSource source) { inputReads++; return new byte[]{1,2}; }
        public JobRecord.ResultLocation writeResult(ConversionJobRequest request, ConversionResult result) {
            assertEquals("running", records.get(request.jobId()).job().status());
            attempts++; lastDirectory = result.directory();
            if (failUpload) throw new IllegalStateException("Transient Storage failure");
            try {
                for (var file : result.files().entrySet()) downloads.put(file.getKey(),
                        new JobDownload(Files.readAllBytes(file.getValue()), "application/octet-stream", file.getKey()));
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            return new JobRecord.ResultLocation("default", "outgoing", List.of(), result.sheetCount(), result.warningCount());
        }
        public JobDownload readResult(JobRecord.ResultLocation result, String artifact) { return downloads.get(artifact); }
        public JobDownload readArchive(JobRecord.ResultLocation result) { return new JobDownload(new byte[]{1}, "application/zip", "document.zip"); }
        public JobLock lock(String id) {
            if (held) throw new IllegalStateException("Lease held");
            held = true;
            return new JobLock() {
                public void update(JobRecord record) { assertTrue(held); records.put(id, record); transitions.add(record.job().status()); }
                public void close() { held = false; }
            };
        }
    }
}
