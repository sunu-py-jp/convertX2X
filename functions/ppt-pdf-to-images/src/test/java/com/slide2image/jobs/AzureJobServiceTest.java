package com.slide2image.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slide2image.conversion.ConversionException;
import com.slide2image.conversion.ConversionOptions;
import com.slide2image.conversion.ConversionResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AzureJobServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);
    private static final ConversionOptions OPTIONS = new ConversionOptions(1600, "png", null);
    private static final byte[] INPUT = "%PDF-example".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final ConversionResult RESULT = new ConversionResult(new byte[]{1, 2, 3},
            "application/zip", "slides.zip", 3);
    private final MemoryStore store = new MemoryStore();

    private AzureJobService service(AzureJobService.Converter converter) {
        return new AzureJobService(store, converter,
                (input, filename, options, consumer) -> fail("ZIP requests must not use the page sink."),
                (input, filename, options) -> {}, CLOCK);
    }

    private AzureJobService imagesService(AzureJobService.PageConverter converter) {
        return new AzureJobService(store, (input, filename, options) -> fail("Image mode must not build a ZIP."),
                converter, (input, filename, options) -> {}, CLOCK);
    }

    @Test
    void submissionPersistsInputBeforeQueueAndPublishesIndependentJsonRequest() {
        AzureJobService service = service((input, filename, options) -> fail("Submission must not render."));
        JobStatus job = service.submit(INPUT, "C:\\uploads\\deck.pdf", OPTIONS);
        assertEquals("queued", job.status());
        assertEquals("deck.pdf", job.filename());
        assertEquals(1, store.messages.size());
        ConversionJobRequest request = ConversionJobRequest.parse(store.messages.getFirst());
        assertEquals(job.id(), request.jobId());
        assertEquals(new ConversionJobRequest.BlobSource("conversion-jobs", job.id() + "/input"), request.input());
        assertEquals("deck.pdf", request.filename());
        assertEquals(OPTIONS, request.options());
        assertEquals(UUID.fromString(job.id()).toString(), job.id());
        assertArrayEquals(INPUT, store.readInput(store.jobs.get(job.id()).request().input()));
        assertEquals("2026-09-08T00:00:00Z", job.createdAt());
    }

    @Test
    void submissionValidatesBeforePersistingAnything() {
        AzureJobService service = new AzureJobService(store, (input, filename, options) -> RESULT,
                (input, filename, options, consumer) -> fail("Submission must not render."),
                (input, filename, options) -> { throw new ConversionException(413, "TOO_LARGE", "Too large."); }, CLOCK);
        assertEquals(413, assertThrows(ConversionException.class,
                () -> service.submit(INPUT, "deck.pdf", OPTIONS)).statusCode());
        assertTrue(store.jobs.isEmpty());
        assertTrue(store.messages.isEmpty());
    }

    @Test
    void completedJobDownloadsBytesAndDuplicateDeliveriesDoNotRenderAgain() {
        AtomicInteger calls = new AtomicInteger();
        AzureJobService service = service((input, filename, options) -> {
            calls.incrementAndGet();
            assertArrayEquals(INPUT, input);
            assertEquals(OPTIONS, options);
            return RESULT;
        });
        JobStatus job = service.submit(INPUT, "deck.pdf", OPTIONS);
        service.process(message(job.id()));
        service.process(message(job.id()));
        service.poison(message(job.id()));
        JobStatus completed = service.find(job.id()).orElseThrow();
        assertEquals("succeeded", completed.status());
        assertEquals(3, completed.pageCount());
        assertEquals(1, calls.get());
        assertArrayEquals(RESULT.bytes(), service.download(job.id()).bytes());
        assertEquals(RESULT.filename(), service.download(job.id()).filename());
        assertEquals(List.of("running", "succeeded"), store.transitions);
    }

    @Test
    void deterministicInvalidInputFailsOnceAndRetainsSafeErrorDetails() {
        AtomicInteger calls = new AtomicInteger();
        AzureJobService service = service((input, filename, options) -> {
            calls.incrementAndGet();
            throw new ConversionException(422, "INVALID_DOCUMENT", "The document is invalid.");
        });
        JobStatus job = service.submit(INPUT, "deck.pdf", OPTIONS);
        service.process(message(job.id()));
        service.process(message(job.id()));
        service.poison(message(job.id()));
        JobStatus failed = service.find(job.id()).orElseThrow();
        assertEquals("failed", failed.status());
        assertEquals("INVALID_DOCUMENT", failed.errorCode());
        assertEquals("The document is invalid.", failed.errorMessage());
        assertEquals(1, calls.get());
        assertTrue(store.results.isEmpty());
    }

    @Test
    void temporaryConversionFailureIsRetriedThenCanComplete() {
        AtomicInteger calls = new AtomicInteger();
        AzureJobService service = service((input, filename, options) -> {
            if (calls.getAndIncrement() == 0) {
                throw new ConversionException(503, "CONVERSION_BUSY", "Please retry.");
            }
            return RESULT;
        });
        JobStatus job = service.submit(INPUT, "deck.pdf", OPTIONS);
        assertEquals(503, assertThrows(ConversionException.class, () -> service.process(message(job.id()))).statusCode());
        assertEquals("running", service.find(job.id()).orElseThrow().status());
        assertFalse(store.locked);
        service.process(message(job.id()));
        assertEquals("succeeded", service.find(job.id()).orElseThrow().status());
        assertEquals(2, calls.get());
    }

    @Test
    void storageFailureEscapesForQueueRetryAndPoisonRecordsFinalFailure() {
        AzureJobService service = service((input, filename, options) -> RESULT);
        JobStatus job = service.submit(INPUT, "deck.pdf", OPTIONS);
        store.readFailure = new IllegalStateException("Internal storage details should not reach status.");
        assertThrows(IllegalStateException.class, () -> service.process(message(job.id())));
        service.poison(message(job.id()));
        JobStatus failed = service.find(job.id()).orElseThrow();
        assertEquals("failed", failed.status());
        assertEquals("PROCESSING_FAILED", failed.errorCode());
        assertFalse(failed.errorMessage().contains("Internal storage"));
        assertFalse(store.locked);
    }

    @Test
    void competingDeliveryCannotRunWhileAnotherWorkerHoldsLease() {
        AtomicInteger calls = new AtomicInteger();
        AzureJobService service = service((input, filename, options) -> {
            calls.incrementAndGet();
            return RESULT;
        });
        JobStatus job = service.submit(INPUT, "deck.pdf", OPTIONS);
        try (JobStore.JobLock ignored = store.lock(job.id())) {
            assertThrows(IllegalStateException.class, () -> service.process(message(job.id())));
            assertThrows(IllegalStateException.class, () -> service.poison(message(job.id())));
        }
        assertEquals(0, calls.get());
        assertEquals("queued", service.find(job.id()).orElseThrow().status());
        service.process(message(job.id()));
        assertEquals("succeeded", service.find(job.id()).orElseThrow().status());
    }

    @Test
    void resultPublicationFailureCanRetryWithoutPublishingTheOldOutput() {
        AzureJobService service = service((input, filename, options) -> RESULT);
        JobStatus job = service.submit(INPUT, "deck.pdf", OPTIONS);
        store.failSuccessUpdate = true;
        assertThrows(IllegalStateException.class, () -> service.process(message(job.id())));
        String abandonedResult = store.results.keySet().iterator().next();
        assertEquals("running", service.find(job.id()).orElseThrow().status());
        service.process(message(job.id()));
        JobRecord completed = store.jobs.get(job.id());
        assertEquals("succeeded", completed.job().status());
        assertNotEquals(abandonedResult, completed.result().blobName());
        assertArrayEquals(RESULT.bytes(), service.download(job.id()).bytes());
    }

    @Test
    void missingAndPendingDownloadsHaveDistinctErrors() {
        AzureJobService service = service((input, filename, options) -> RESULT);
        String missing = UUID.randomUUID().toString();
        assertTrue(service.find(missing).isEmpty());
        assertEquals(404, assertThrows(ConversionException.class, () -> service.download(missing)).statusCode());
        JobStatus queued = service.submit(INPUT, "deck.pdf", OPTIONS);
        assertEquals(409, assertThrows(ConversionException.class, () -> service.download(queued.id())).statusCode());
    }

    @Test
    void malformedIdentifiersCannotAddressOtherBlobs() {
        AzureJobService service = service((input, filename, options) -> RESULT);
        for (String invalid : List.of("../status", "1-1-1-1-1", "", "jobs/a", " ")) {
            assertEquals(400, assertThrows(ConversionException.class, () -> service.find(invalid)).statusCode());
            assertThrows(ConversionException.class, () -> service.process(invalid));
            assertDoesNotThrow(() -> service.poison(invalid));
        }
        assertDoesNotThrow(() -> service.poison(UUID.randomUUID().toString()));
    }

    @Test
    void filenamesRemainDisplayDataAndAreNeverStoragePaths() {
        AzureJobService service = service((input, filename, options) -> RESULT);
        JobStatus job = service.submit(INPUT, "../../slides\r\n.pdf", OPTIONS);
        assertEquals("slides__.pdf", job.filename());
        assertThrows(ConversionException.class, () -> service.submit(INPUT, "../", OPTIONS));
        assertThrows(ConversionException.class, () -> service.submit(INPUT, "..", OPTIONS));
        assertThrows(ConversionException.class, () -> service.submit(INPUT, "a".repeat(256), OPTIONS));
    }

    @Test
    void persistedStatusRoundTripsIncludingOptionsAndResultMetadata() throws Exception {
        AzureJobService service = service((input, filename, options) -> RESULT);
        JobStatus job = service.submit(INPUT, "日本語.pdf", OPTIONS);
        service.process(message(job.id()));
        JobRecord record = store.jobs.get(job.id());
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(record, mapper.readValue(mapper.writeValueAsBytes(record), JobRecord.class));
    }

    @Test
    void externalQueueRequestNeedsNoApiOrPreexistingStatusAndUsesItsBlobReferences() {
        ConversionJobRequest request = externalRequest();
        store.inputs.put(inputKey(request.input()), INPUT);
        AtomicInteger calls = new AtomicInteger();
        AzureJobService service = service((input, filename, options) -> {
            calls.incrementAndGet();
            assertArrayEquals(INPUT, input);
            assertEquals("deck.pdf", filename);
            assertEquals(new ConversionOptions(null, "png", null), options);
            return RESULT;
        });
        assertTrue(store.jobs.isEmpty());
        service.process(request.toJson());
        JobRecord completed = store.jobs.get(request.jobId());
        assertEquals("succeeded", completed.job().status());
        assertEquals(request, completed.request());
        assertEquals("archive", completed.result().storage());
        assertEquals("external-output", completed.result().container());
        assertTrue(completed.result().blobName().startsWith("reports/" + request.jobId() + "/results/"));
        service.process(request.toJson());
        service.poison(request.toJson());
        assertEquals(1, calls.get());
        assertTrue(store.messages.isEmpty());
    }

    @Test
    void reusedJobIdWithDifferentRequestDoesNotChangeExistingJobEvenOnPoison() {
        ConversionJobRequest request = externalRequest();
        store.inputs.put(inputKey(request.input()), INPUT);
        AzureJobService service = service((input, filename, options) -> RESULT);
        service.process(request.toJson());
        JobRecord original = store.jobs.get(request.jobId());
        ConversionJobRequest different = new ConversionJobRequest(1, request.jobId(), request.input(),
                new ConversionJobRequest.BlobOutput("archive", "another-output", "different"), request.filename(), request.options());
        ConversionException failure = assertThrows(ConversionException.class, () -> service.process(different.toJson()));
        assertEquals("JOB_ID_CONFLICT", failure.code());
        service.poison(different.toJson());
        assertEquals(original, store.jobs.get(request.jobId()));
        assertEquals(1, store.results.size());
    }

    @Test
    void inputOnlyStorageCannotBeSelectedForOutput() {
        ConversionJobRequest request = externalRequest();
        ConversionJobRequest forbidden = new ConversionJobRequest(1, request.jobId(), request.input(),
                new ConversionJobRequest.BlobOutput("source", "output-container", ""), request.filename(), request.options());
        AzureJobService service = service((input, filename, options) -> fail("Unregistered output must fail before rendering."));
        service.process(forbidden.toJson());
        assertEquals("failed", service.find(request.jobId()).orElseThrow().status());
        assertEquals("UNKNOWN_OUTPUT_STORAGE", service.find(request.jobId()).orElseThrow().errorCode());
        assertTrue(store.results.isEmpty());
    }

    @Test
    void missingExternalInputIsRecordedAsTerminalFailure() {
        ConversionJobRequest request = externalRequest();
        AzureJobService service = service((input, filename, options) -> fail("Missing input must not render."));
        service.process(request.toJson());
        assertEquals("INPUT_NOT_FOUND", service.find(request.jobId()).orElseThrow().errorCode());
        assertEquals("failed", service.find(request.jobId()).orElseThrow().status());
    }

    @Test
    void exhaustedExternalRequestCanCreateItsFailureStatusWithoutApi() {
        ConversionJobRequest request = externalRequest();
        AzureJobService service = service((input, filename, options) -> fail("Poison handling must not render."));
        service.poison(request.toJson());
        assertEquals("failed", service.find(request.jobId()).orElseThrow().status());
        assertEquals("PROCESSING_FAILED", service.find(request.jobId()).orElseThrow().errorCode());
    }

    @Test
    void individualImagesAreStoredDuringConversionAndManifestIsPublishedOnlyAtCompletion() throws Exception {
        ConversionJobRequest request = imageRequest();
        store.inputs.put(inputKey(request.input()), INPUT);
        AtomicInteger calls = new AtomicInteger();
        AzureJobService service = imagesService((input, filename, options, consumer) -> {
            calls.incrementAndGet();
            assertArrayEquals(INPUT, input);
            assertEquals("running", store.jobs.get(request.jobId()).job().status());
            assertNull(store.jobs.get(request.jobId()).result());
            consumer.accept(1, page(1));
            assertEquals(1, store.results.size(), "A page must be stored before the following page is rendered.");
            consumer.accept(2, page(2));
            assertEquals(2, store.results.size());
            assertNull(store.jobs.get(request.jobId()).result(), "Partial pages must not be published as a completed result.");
            return 2;
        });
        service.process(request.toJson());
        JobRecord completed = store.jobs.get(request.jobId());
        assertEquals("succeeded", completed.job().status());
        assertEquals(2, completed.job().pageCount());
        assertEquals("application/json", completed.result().contentType());
        ConversionResult manifest = service.download(request.jobId());
        assertEquals("manifest.json", manifest.filename());
        var json = new ObjectMapper().readTree(manifest.bytes());
        assertEquals(2, json.path("images").size());
        assertEquals("images", json.path("mode").asText());
        for (var image : json.path("images")) {
            assertEquals("archive", image.path("storage").asText());
            assertTrue(store.results.containsKey(image.path("blobName").asText()));
        }
        assertTrue(store.results.values().stream().noneMatch(result -> result.contentType().equals("application/zip")));
        service.process(request.toJson());
        service.poison(request.toJson());
        assertEquals(1, calls.get());
        assertEquals(completed, store.jobs.get(request.jobId()));
    }

    @Test
    void partialImageUploadFailureRetriesIntoAnotherAttemptWithoutPublishingPartialPages() {
        ConversionJobRequest request = imageRequest();
        store.inputs.put(inputKey(request.input()), INPUT);
        store.failImagePage = 2;
        AtomicInteger calls = new AtomicInteger();
        AzureJobService service = imagesService((input, filename, options, consumer) -> {
            calls.incrementAndGet();
            consumer.accept(1, page(1));
            consumer.accept(2, page(2));
            return 2;
        });
        assertThrows(IllegalStateException.class, () -> service.process(request.toJson()));
        assertEquals("running", service.find(request.jobId()).orElseThrow().status());
        assertNull(store.jobs.get(request.jobId()).result());
        assertEquals(409, assertThrows(ConversionException.class, () -> service.download(request.jobId())).statusCode());
        String abandoned = store.results.keySet().iterator().next();
        assertEquals(1, store.results.size());
        service.process(request.toJson());
        assertEquals(2, calls.get());
        assertEquals("succeeded", service.find(request.jobId()).orElseThrow().status());
        assertFalse(store.jobs.get(request.jobId()).result().blobName().startsWith(abandoned.substring(0, abandoned.lastIndexOf('/'))));
        assertEquals(4, store.results.size());
    }

    @Test
    void deterministicImageFailureDoesNotPublishManifestOrRepeatTerminalJob() {
        ConversionJobRequest request = imageRequest();
        store.inputs.put(inputKey(request.input()), INPUT);
        AtomicInteger calls = new AtomicInteger();
        AzureJobService service = imagesService((input, filename, options, consumer) -> {
            calls.incrementAndGet();
            consumer.accept(1, page(1));
            throw new ConversionException(413, "OUTPUT_LIMIT_EXCEEDED", "Images exceed the output limit.");
        });
        service.process(request.toJson());
        service.process(request.toJson());
        JobRecord failed = store.jobs.get(request.jobId());
        assertEquals("failed", failed.job().status());
        assertEquals("OUTPUT_LIMIT_EXCEEDED", failed.job().errorCode());
        assertNull(failed.result());
        assertEquals(1, store.results.size());
        assertEquals(1, calls.get());
    }

    @Test
    void manifestOrStatusPublicationFailuresRetryWithANewCompleteAttempt() {
        for (boolean failManifest : new boolean[] {true, false}) {
            store.results.clear();
            ConversionJobRequest request = imageRequest();
            store.inputs.put(inputKey(request.input()), INPUT);
            store.failManifest = failManifest;
            store.failSuccessUpdate = !failManifest;
            AzureJobService service = imagesService((input, filename, options, consumer) -> {
                consumer.accept(1, page(1));
                consumer.accept(2, page(2));
                return 2;
            });
            assertThrows(IllegalStateException.class, () -> service.process(request.toJson()));
            assertNull(store.jobs.get(request.jobId()).result());
            String oldPage = store.results.keySet().iterator().next();
            String oldAttempt = oldPage.substring(0, oldPage.lastIndexOf('/'));
            service.process(request.toJson());
            JobRecord complete = store.jobs.get(request.jobId());
            assertEquals("succeeded", complete.job().status());
            assertFalse(complete.result().blobName().startsWith(oldAttempt));
            assertEquals("application/json", service.download(request.jobId()).contentType());
        }
    }

    @Test
    void outputModeCannotBeChangedForAnExistingJobId() {
        ConversionJobRequest request = externalRequest();
        store.inputs.put(inputKey(request.input()), INPUT);
        AzureJobService service = service((input, filename, options) -> RESULT);
        service.process(request.toJson());
        JobRecord original = store.jobs.get(request.jobId());
        ConversionJobRequest different = new ConversionJobRequest(1, request.jobId(), request.input(),
                new ConversionJobRequest.BlobOutput("archive", "external-output", "reports", "images"), null, null).normalized();
        assertEquals("JOB_ID_CONFLICT", assertThrows(ConversionException.class,
                () -> service.process(different.toJson())).code());
        service.poison(different.toJson());
        assertEquals(original, store.jobs.get(request.jobId()));
    }

    private static ConversionResult page(int number) {
        return new ConversionResult(new byte[] {(byte) number}, "image/png", "page-%04d.png".formatted(number), 1);
    }

    private static ConversionJobRequest imageRequest() {
        ConversionJobRequest base = externalRequest();
        return new ConversionJobRequest(1, base.jobId(), base.input(),
                new ConversionJobRequest.BlobOutput("archive", "external-output", "reports", "images"), null, null).normalized();
    }

    private static ConversionJobRequest externalRequest() {
        return new ConversionJobRequest(1, UUID.randomUUID().toString(),
                new ConversionJobRequest.BlobSource("source", "external-input", "incoming/deck.pdf"),
                new ConversionJobRequest.BlobOutput("archive", "external-output", "reports"), null, null).normalized();
    }

    private String message(String id) { return store.jobs.get(id).request().toJson(); }

    private static String inputKey(ConversionJobRequest.BlobSource input) {
        return input.storage() + "/" + input.container() + "/" + input.blobName();
    }

    private static final class MemoryStore implements JobStore {
        final BlobStorageProfiles profiles = BlobStorageProfiles.from(Map.of(
                "CONVERSION_INPUT_STORAGE_SOURCE", "source-connection",
                "CONVERSION_OUTPUT_STORAGE_ARCHIVE", "output-connection"), "default-connection");
        final Map<String, JobRecord> jobs = new HashMap<>();
        final Map<String, byte[]> inputs = new HashMap<>();
        final Map<String, ConversionResult> results = new HashMap<>();
        final List<String> messages = new ArrayList<>();
        final List<String> transitions = new ArrayList<>();
        boolean locked;
        boolean failSuccessUpdate;
        int failImagePage;
        boolean failManifest;
        RuntimeException readFailure;

        @Override public void create(JobRecord job, byte[] input) {
            inputs.put(inputKey(job.request().input()), input.clone());
            ensure(job);
        }
        @Override public void ensure(JobRecord job) { jobs.putIfAbsent(job.job().id(), job); }
        @Override public void validateLocations(ConversionJobRequest request) {
            profiles.inputConnection(request.input().storage());
            profiles.outputConnection(request.output().storage());
        }
        @Override public void enqueue(String message) {
            ConversionJobRequest request = ConversionJobRequest.parse(message);
            assertTrue(jobs.containsKey(request.jobId()));
            assertTrue(inputs.containsKey(inputKey(request.input())));
            messages.add(message);
        }
        @Override public Optional<JobRecord> find(String id) {
            return Optional.ofNullable(jobs.get(id));
        }
        @Override public byte[] readInput(ConversionJobRequest.BlobSource source) {
            if (readFailure != null) throw readFailure;
            byte[] input = inputs.get(inputKey(source));
            if (input == null) throw new ConversionException(422, "INPUT_NOT_FOUND", "Input does not exist.");
            return input.clone();
        }
        @Override public JobRecord.ResultLocation writeResult(ConversionJobRequest request, ConversionResult result) {
            String key = request.output().prefix() + "/" + request.jobId() + "/results/" + UUID.randomUUID() + "/" + result.filename();
            results.put(key, result);
            return new JobRecord.ResultLocation(request.output().storage(), request.output().container(), key,
                    result.contentType(), result.filename(), result.pageCount());
        }
        @Override public ConversionResult readResult(JobRecord.ResultLocation result) {
            return results.get(result.blobName());
        }
        @Override public ImageOutput beginImages(ConversionJobRequest request) {
            String root = request.output().prefix() + "/" + request.jobId() + "/results/" + UUID.randomUUID() + "/";
            List<Map<String, Object>> images = new ArrayList<>();
            return new ImageOutput() {
                @Override public void writePage(int number, ConversionResult image) {
                    if (failImagePage == number) {
                        failImagePage = 0;
                        throw new IllegalStateException("Temporary page upload failure.");
                    }
                    String path = root + image.filename();
                    results.put(path, image);
                    images.add(Map.of("page", number, "storage", request.output().storage(),
                            "container", request.output().container(), "blobName", path,
                            "contentType", image.contentType(), "filename", image.filename(), "sizeBytes", image.bytes().length));
                }
                @Override public JobRecord.ResultLocation finish() {
                    if (failManifest) {
                        failManifest = false;
                        throw new IllegalStateException("Temporary manifest upload failure.");
                    }
                    byte[] bytes;
                    try {
                        bytes = new ObjectMapper().writeValueAsBytes(Map.of("version", 1, "jobId", request.jobId(),
                                "mode", "images", "pageCount", images.size(), "images", images));
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                    String path = root + "manifest.json";
                    ConversionResult result = new ConversionResult(bytes, "application/json", "manifest.json", images.size());
                    results.put(path, result);
                    return new JobRecord.ResultLocation(request.output().storage(), request.output().container(), path,
                            result.contentType(), result.filename(), result.pageCount());
                }
            };
        }
        @Override public JobLock lock(String id) {
            if (locked) throw new IllegalStateException("The job already has an active lease.");
            locked = true;
            return new JobLock() {
                @Override public void update(JobRecord job) {
                    if (failSuccessUpdate && "succeeded".equals(job.job().status())) {
                        failSuccessUpdate = false;
                        throw new IllegalStateException("The processing lease was lost.");
                    }
                    transitions.add(job.job().status());
                    jobs.put(job.job().id(), job);
                }
                @Override public void close() { locked = false; }
            };
        }
    }
}
