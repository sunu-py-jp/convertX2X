package com.convertx2x.office2md.jobs;

import com.convertx2x.office2md.conversion.ConversionException;
import com.convertx2x.office2md.conversion.ConversionResult;
import com.convertx2x.office2md.conversion.OfficeMarkdownService;
import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ImageMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class AzureJobService implements JobService {
    public static final String QUEUE_NAME = "office2md-jobs";
    public static final String CONTAINER_NAME = "office2md-jobs";

    @FunctionalInterface
    interface Converter {
        ConversionResult convert(byte[] input, String filename);
    }

    @FunctionalInterface
    interface ModeConverter {
        ConversionResult convert(byte[] input, String filename, ImageMode imageMode);
    }

    @FunctionalInterface
    interface Validator {
        void validate(byte[] input, String filename);
    }

    private final JobStore store;
    private final ModeConverter converter;
    private final Validator validator;
    private final Consumer<ImageMode> imageModeValidator;
    private final Clock clock;

    public AzureJobService(String connectionString, OfficeMarkdownService converter, ConversionLimits limits,
                           BlobStorageProfiles profiles) {
        this(new AzureJobStore(connectionString, limits, profiles),
                converter::convert, converter::validate, converter::validateImageMode, Clock.systemUTC());
    }

    public AzureJobService(IntegrationSettings settings, OfficeMarkdownService converter, ConversionLimits limits,
                           BlobStorageProfiles profiles) {
        this(new AzureJobStore(settings, limits, profiles),
                converter::convert, converter::validate, converter::validateImageMode, Clock.systemUTC());
    }

    AzureJobService(JobStore store, Converter converter, Validator validator, Clock clock) {
        this(store, (input, filename, ignored) -> converter.convert(input, filename), validator, mode -> {
            if (mode != ImageMode.IGNORE) throw new ConversionException(503, "OCR_NOT_CONFIGURED", "Image OCR is not configured.");
        }, clock);
    }

    AzureJobService(JobStore store, ModeConverter converter, Validator validator, Consumer<ImageMode> imageModeValidator, Clock clock) {
        this.store = store;
        this.converter = converter;
        this.validator = validator;
        this.imageModeValidator = imageModeValidator;
        this.clock = clock;
    }

    @Override
    public JobStatus submit(byte[] input, String filename) {
        return submit(input, filename, ImageMode.IGNORE);
    }

    @Override
    public JobStatus submit(byte[] input, String filename, ImageMode imageMode) {
        Objects.requireNonNull(imageMode);
        imageModeValidator.accept(imageMode);
        String safeName = safeFilename(filename);
        validator.validate(input, safeName);
        String id = UUID.randomUUID().toString();
        ConversionJobRequest request = new ConversionJobRequest(
                imageMode == ImageMode.OCR ? 2 : 1, id,
                new ConversionJobRequest.BlobSource(CONTAINER_NAME, id + "/input"),
                new ConversionJobRequest.BlobOutput(CONTAINER_NAME, ""), safeName,
                java.util.Map.of(), null, null, imageMode.wireValue()).normalized();
        JobRecord record = queued(request);
        JobRecord submitting = new JobRecord(record.job(), null, request, null, null, null, true);
        store.ensure(submitting);
        // A worker cannot observe partial HTTP input or uncommitted submission while this lease is held.
        try (JobStore.JobLock lock = store.lock(id)) {
            try {
                store.create(submitting, input);
                store.enqueue(request.toJson());
                lock.update(record);
            } catch (RuntimeException failure) {
                try { lock.update(new JobRecord(transition(submitting.job(), "failed", null, null,
                        "SUBMISSION_FAILED", "The asynchronous request could not be submitted."), null, request)); }
                catch (RuntimeException ignored) { /* A later maintenance sweep expires the abandoned submitting state. */ }
                throw failure;
            }
        }
        return record.job();
    }

    @Override
    public Optional<JobStatus> find(String id) {
        return store.find(validateId(id)).map(JobRecord::job);
    }

    @Override
    public JobDownload download(String id, String artifact) {
        return store.readResult(completed(id).result(), artifact);
    }

    @Override
    public JobDownload downloadResult(String id) {
        JobRecord record = completed(id);
        return store.readResult(record.result(), "document.md");
    }

    @Override
    public JobDownload archive(String id) {
        return store.readArchive(completed(id).result());
    }

    private JobRecord completed(String id) {
        JobRecord record = requireJob(validateId(id));
        if (!"succeeded".equals(record.job().status())) {
            throw new ConversionException(409, "JOB_NOT_READY", "The job has not completed successfully.");
        }
        if (record.artifactsExpiredAt() != null) throw new ConversionException(410, "JOB_RESULT_EXPIRED", "The result retention period has ended.");
        if (record.result() == null) throw new IllegalStateException("The completed job has no result metadata.");
        return record;
    }

    @Override
    public void process(String message) {
        ConversionJobRequest request = ConversionJobRequest.parse(message);
        String id = request.jobId();
        // Producers need only a source blob and JSON. The worker owns job state creation.
        store.ensure(queued(request));
        // A renewable blob lease serializes deliveries, including poison handling.
        try (JobStore.JobLock lock = store.lock(id)) {
            JobRecord record = requireJob(id);
            checkRequest(record, request);
            if (terminal(record.job())) {
                deliver(lock, record);
                return;
            }
            JobStatus running = transition(record.job(), "running", null, null, null, null);
            lock.update(new JobRecord(running, null, request).withTrackingVersion(record.artifactTrackingVersion()));
            String inputETag = null;
            try {
                ImageMode imageMode = ImageMode.parse(request.imageMode());
                imageModeValidator.accept(imageMode);
                store.validateLocations(request);
                JobStore.InputData input = store.readInputVersioned(request.input());
                inputETag = input.eTag();
                try (ConversionResult result = converter.convert(input.bytes(), running.filename(),
                        imageMode)) {
                    JobRecord.ResultLocation location = store.writeResult(request, result, inputETag);
                    // Publish only after every artifact from one attempt has reached Storage.
                    lock.update(new JobRecord(transition(running, "succeeded", location.sectionCount(),
                            location.warningCount(), null, null), location, request, inputETag, null, null).withTrackingVersion(record.artifactTrackingVersion()));
                }
            } catch (ConversionException failure) {
                if (failure.statusCode() >= 500) {
                    throw failure;
                }
                lock.update(new JobRecord(transition(running, "failed", null, null,
                        failure.code(), failure.getMessage()), null, request, inputETag, null, null).withTrackingVersion(record.artifactTrackingVersion()));
            }
            deliver(lock, requireJob(id));
        }
    }

    @Override
    public void poison(String message) {
        final ConversionJobRequest request;
        try {
            request = ConversionJobRequest.parse(message);
        } catch (ConversionException invalidMessage) {
            // There is no addressable job to update for a malformed queue message.
            return;
        }
        String id = request.jobId();
        store.ensure(queued(request));
        Optional<JobRecord> existing = store.find(id);
        if (existing.isEmpty() || !sameRequest(existing.get().request(), request)) {
            return;
        }
        try (JobStore.JobLock lock = store.lock(id)) {
            JobRecord record = requireJob(id);
            if (sameRequest(record.request(), request) && !terminal(record.job())) {
                lock.update(new JobRecord(transition(record.job(), "failed", null, null,
                        "PROCESSING_FAILED", "Conversion failed after repeated processing attempts."), null, request).withTrackingVersion(record.artifactTrackingVersion()));
            }
            deliver(lock, requireJob(id));
        }
    }

    /** Notification failure never changes conversion success and never reruns the converter. */
    private JobRecord deliver(JobStore.JobLock lock, JobRecord record) {
        if (!record.pendingNotification()) return record;
        try {
            store.notifyResult(record);
            JobRecord delivered = record.withNotificationSent(now());
            lock.update(delivered);
            return delivered;
        } catch (RuntimeException failure) {
            // Persisted terminal state remains pending. Queue delivery is at least once; eventId is stable.
            return record;
        }
    }

    @Override public void maintenance() {
        store.maintenance(id -> {
            try (JobStore.JobLock lock = store.lock(id)) {
                JobRecord record = requireJob(id);
                if (record.submissionPending() && "queued".equals(record.job().status())) { store.cleanup(record, lock, Instant.now(clock)); return; }
                if (!terminal(record.job())) return;
                record = deliver(lock, record);
                if (!record.pendingNotification()) store.cleanup(record, lock, Instant.now(clock));
            }
        });
    }

    private JobRecord requireJob(String id) {
        return store.find(id).orElseThrow(() -> new ConversionException(404, "JOB_NOT_FOUND", "Job not found."));
    }

    private JobRecord queued(ConversionJobRequest request) {
        String now = now();
        return new JobRecord(new JobStatus(request.jobId(), "queued", request.filename(), now, now, null, null, null, null), null, request);
    }

    private static void checkRequest(JobRecord record, ConversionJobRequest request) {
        if (!sameRequest(record.request(), request)) {
            throw new ConversionException(409, "JOB_ID_CONFLICT", "The job ID is already assigned to a different request.");
        }
    }

    private static boolean sameRequest(ConversionJobRequest stored, ConversionJobRequest incoming) {
        // Records written before imageMode existed deserialize it as null (ignore).
        return stored != null && Objects.equals(stored.normalized(), incoming);
    }

    private JobStatus transition(JobStatus job, String status, Integer sectionCount, Integer warningCount, String code, String message) {
        return new JobStatus(job.id(), status, job.filename(), job.createdAt(),
                now(), sectionCount, warningCount, code, message);
    }

    private static boolean terminal(JobStatus job) {
        return "succeeded".equals(job.status()) || "failed".equals(job.status());
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    static String validateId(String id) {
        // UUID.fromString alone accepts shortened forms such as 1-1-1-1-1.
        if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new ConversionException(400, "INVALID_JOB_ID", "The job ID must be a UUID.");
        }
        return UUID.fromString(id).toString();
    }

    private static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new ConversionException(400, "MISSING_FILENAME", "A filename is required.");
        }
        String normalized = filename.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (basename.isBlank() || basename.equals(".") || basename.equals("..") || basename.length() > 255) {
            throw new ConversionException(400, "INVALID_FILENAME", "The filename is invalid or too long.");
        }
        return basename;
    }
}
