package com.slide2image.jobs;

import com.slide2image.conversion.ConversionException;
import com.slide2image.conversion.ConversionOptions;
import com.slide2image.conversion.ConversionResult;
import com.slide2image.conversion.ConversionService;
import com.slide2image.conversion.ConversionLimits;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

public final class AzureJobService implements JobService {
    public static final String QUEUE_NAME = "conversion-jobs";
    public static final String CONTAINER_NAME = "conversion-jobs";

    @FunctionalInterface
    interface Converter {
        ConversionResult convert(byte[] input, String filename, ConversionOptions options);
    }

    @FunctionalInterface
    interface PageConverter {
        int convert(byte[] input, String filename, ConversionOptions options, ConversionService.PageConsumer consumer);
    }

    @FunctionalInterface
    interface Validator {
        void validate(byte[] input, String filename, ConversionOptions options);
    }

    private final JobStore store;
    private final Converter converter;
    private final PageConverter pageConverter;
    private final Validator validator;
    private final Clock clock;

    public AzureJobService(String connectionString, ConversionService converter, ConversionLimits limits,
                           BlobStorageProfiles profiles) {
        this(new AzureJobStore(connectionString, limits, profiles), converter::convert, converter::convertPages,
                converter::validate, Clock.systemUTC());
    }

    AzureJobService(JobStore store, Converter converter, PageConverter pageConverter, Validator validator, Clock clock) {
        this.store = store;
        this.converter = converter;
        this.pageConverter = pageConverter;
        this.validator = validator;
        this.clock = clock;
    }

    @Override
    public JobStatus submit(byte[] input, String filename, ConversionOptions options) {
        String safeName = safeFilename(filename);
        validator.validate(input, safeName, options);
        String id = UUID.randomUUID().toString();
        ConversionJobRequest request = new ConversionJobRequest(1, id,
                new ConversionJobRequest.BlobSource(CONTAINER_NAME, id + "/input"),
                new ConversionJobRequest.BlobOutput(CONTAINER_NAME, ""), safeName, options).normalized();
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
    public ConversionResult download(String id) {
        JobRecord record = requireJob(validateId(id));
        if (!"succeeded".equals(record.job().status())) {
            throw new ConversionException(409, "JOB_NOT_READY", "The job has not completed successfully.");
        }
        if (record.result() == null) {
            throw new IllegalStateException("The completed job has no result metadata.");
        }
        return store.readResult(record.result());
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
            JobStatus running = transition(record.job(), "running", null, null, null);
            lock.update(new JobRecord(running, null, request));
            try {
                store.validateLocations(request);
                byte[] input = store.readInput(request.input());
                JobRecord.ResultLocation location;
                if (request.output().mode().equals("images")) {
                    JobStore.ImageOutput images = store.beginImages(request);
                    int pageCount = pageConverter.convert(input, running.filename(), running.options(), images::writePage);
                    // Only publish the manifest after every page has been stored successfully.
                    location = images.finish();
                    if (location.pageCount() != pageCount) {
                        throw new IllegalStateException("The image manifest has an unexpected page count.");
                    }
                } else {
                    ConversionResult result = converter.convert(input, running.filename(), running.options());
                    location = store.writeResult(request, result);
                }
                // The success status selects one complete attempt; partial attempts are never published here.
                lock.update(new JobRecord(transition(running, "succeeded", location.pageCount(), null, null), location, request));
            } catch (ConversionException failure) {
                if (failure.statusCode() >= 500) {
                    throw failure;
                }
                lock.update(new JobRecord(transition(running, "failed", null,
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
                lock.update(new JobRecord(transition(record.job(), "failed", null,
                        "PROCESSING_FAILED", "Conversion failed after repeated processing attempts."), null, request));
            }
        }
    }

    private JobRecord requireJob(String id) {
        return store.find(id).orElseThrow(() -> new ConversionException(404, "JOB_NOT_FOUND", "Job not found."));
    }

    private JobRecord queued(ConversionJobRequest request) {
        String now = now();
        return new JobRecord(new JobStatus(request.jobId(), "queued", request.filename(), request.options(),
                now, now, null, null, null), null, request);
    }

    private static void checkRequest(JobRecord record, ConversionJobRequest request) {
        if (!Objects.equals(record.request(), request)) {
            throw new ConversionException(409, "JOB_ID_CONFLICT", "The job ID is already assigned to a different request.");
        }
    }

    private JobStatus transition(JobStatus job, String status, Integer pageCount, String code, String message) {
        return new JobStatus(job.id(), status, job.filename(), job.options(), job.createdAt(),
                now(), pageCount, code, message);
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
