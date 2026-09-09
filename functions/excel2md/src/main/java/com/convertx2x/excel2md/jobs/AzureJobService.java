package com.convertx2x.excel2md.jobs;

import com.convertx2x.excel2md.conversion.ConversionException;
import com.convertx2x.excel2md.conversion.ConversionResult;
import com.convertx2x.excel2md.conversion.ExcelMarkdownService;
import com.convertx2x.excel2md.conversion.ConversionLimits;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

public final class AzureJobService implements JobService {
    public static final String QUEUE_NAME = "excel2md-jobs";
    public static final String CONTAINER_NAME = "excel2md-jobs";

    @FunctionalInterface
    interface Converter {
        ConversionResult convert(byte[] input, String filename);
    }

    @FunctionalInterface
    interface Validator {
        void validate(byte[] input, String filename);
    }

    private final JobStore store;
    private final Converter converter;
    private final Validator validator;
    private final Clock clock;

    public AzureJobService(String connectionString, ExcelMarkdownService converter, ConversionLimits limits,
                           BlobStorageProfiles profiles) {
        this(new AzureJobStore(connectionString, limits, profiles), converter::convert, converter::validate, Clock.systemUTC());
    }

    AzureJobService(JobStore store, Converter converter, Validator validator, Clock clock) {
        this.store = store;
        this.converter = converter;
        this.validator = validator;
        this.clock = clock;
    }

    @Override
    public JobStatus submit(byte[] input, String filename) {
        String safeName = safeFilename(filename);
        validator.validate(input, safeName);
        String id = UUID.randomUUID().toString();
        ConversionJobRequest request = new ConversionJobRequest(1, id,
                new ConversionJobRequest.BlobSource(CONTAINER_NAME, id + "/input"),
                new ConversionJobRequest.BlobOutput(CONTAINER_NAME, ""), safeName).normalized();
        JobRecord record = queued(request);
        // Persist the input and status before publishing; a worker can run immediately.
        store.create(record, input);
        store.enqueue(request.toJson());
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
    public JobDownload archive(String id) {
        return store.readArchive(completed(id).result());
    }

    private JobRecord completed(String id) {
        JobRecord record = requireJob(validateId(id));
        if (!"succeeded".equals(record.job().status())) {
            throw new ConversionException(409, "JOB_NOT_READY", "The job has not completed successfully.");
        }
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
                return;
            }
            JobStatus running = transition(record.job(), "running", null, null, null, null);
            lock.update(new JobRecord(running, null, request));
            try {
                store.validateLocations(request);
                byte[] input = store.readInput(request.input());
                try (ConversionResult result = converter.convert(input, running.filename())) {
                    JobRecord.ResultLocation location = store.writeResult(request, result);
                    // Publish only after every artifact from one attempt has reached Storage.
                    lock.update(new JobRecord(transition(running, "succeeded", location.sheetCount(),
                            location.warningCount(), null, null), location, request));
                }
            } catch (ConversionException failure) {
                if (failure.statusCode() >= 500) {
                    throw failure;
                }
                lock.update(new JobRecord(transition(running, "failed", null, null,
                        failure.code(), failure.getMessage()), null, request));
            }
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
        if (existing.isEmpty() || !Objects.equals(existing.get().request(), request) || terminal(existing.get().job())) {
            return;
        }
        try (JobStore.JobLock lock = store.lock(id)) {
            JobRecord record = requireJob(id);
            if (Objects.equals(record.request(), request) && !terminal(record.job())) {
                lock.update(new JobRecord(transition(record.job(), "failed", null, null,
                        "PROCESSING_FAILED", "Conversion failed after repeated processing attempts."), null, request));
            }
        }
    }

    private JobRecord requireJob(String id) {
        return store.find(id).orElseThrow(() -> new ConversionException(404, "JOB_NOT_FOUND", "Job not found."));
    }

    private JobRecord queued(ConversionJobRequest request) {
        String now = now();
        return new JobRecord(new JobStatus(request.jobId(), "queued", request.filename(), now, now, null, null, null, null), null, request);
    }

    private static void checkRequest(JobRecord record, ConversionJobRequest request) {
        if (!Objects.equals(record.request(), request)) {
            throw new ConversionException(409, "JOB_ID_CONFLICT", "The job ID is already assigned to a different request.");
        }
    }

    private JobStatus transition(JobStatus job, String status, Integer sheetCount, Integer warningCount, String code, String message) {
        return new JobStatus(job.id(), status, job.filename(), job.createdAt(),
                now(), sheetCount, warningCount, code, message);
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
