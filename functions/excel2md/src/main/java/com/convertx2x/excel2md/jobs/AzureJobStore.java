package com.convertx2x.excel2md.jobs;

import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.core.http.RequestConditions;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobInputStreamOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.specialized.BlobLeaseClient;
import com.azure.storage.blob.specialized.BlobLeaseClientBuilder;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueClientBuilder;
import com.azure.storage.queue.QueueMessageEncoding;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.convertx2x.excel2md.conversion.ConversionException;
import com.convertx2x.excel2md.conversion.ConversionLimits;
import com.convertx2x.excel2md.conversion.ConversionResult;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import com.azure.storage.blob.models.ParallelTransferOptions;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class AzureJobStore implements JobStore {
    private static final Duration STORAGE_TIMEOUT = Duration.ofSeconds(30);
    private final BlobServiceClient blobs;
    private final BlobContainerClient container;
    private final QueueClient queue;
    private final ConversionLimits limits;
    private final BlobStorageProfiles profiles;
    private final Map<String, BlobServiceClient> inputClients = new ConcurrentHashMap<>();
    private final Map<String, BlobServiceClient> outputClients = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean initialized;

    AzureJobStore(String connectionString) {
        this(connectionString, ConversionLimits.defaults());
    }

    AzureJobStore(String connectionString, ConversionLimits limits) {
        this(connectionString, limits, BlobStorageProfiles.from(Map.of(), connectionString));
    }

    AzureJobStore(String connectionString, ConversionLimits limits, BlobStorageProfiles profiles) {
        if (connectionString == null || connectionString.isBlank()) {
            throw new IllegalArgumentException("The conversion storage connection is not configured.");
        }
        this.limits = limits;
        this.profiles = profiles;
        blobs = configuredClient(connectionString);
        container = blobs.getBlobContainerClient(AzureJobService.CONTAINER_NAME);
        try {
            queue = new QueueClientBuilder().connectionString(connectionString)
                    .queueName(AzureJobService.QUEUE_NAME)
                    .messageEncoding(QueueMessageEncoding.BASE64).buildClient();
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("The configured queue connection is invalid.");
        }
    }

    @Override
    public void create(JobRecord job, byte[] input) {
        validateLocations(job.request());
        initialize();
        checkSize(input.length, limits.maxInputBytes(), true);
        ConversionJobRequest.BlobSource source = job.request().input();
        BlobContainerClient inputContainer = inputBlobs(source.storage()).getBlobContainerClient(source.container());
        inputContainer.createIfNotExists();
        inputContainer.getBlobClient(source.blobName()).upload(BinaryData.fromBytes(input), false);
        ensure(job);
    }

    @Override
    public void ensure(JobRecord job) {
        initialize();
        try {
            // A directly queued request creates its own status. An existing job is never reset.
            blob(job.job().id(), "status.json").uploadWithResponse(new BlobParallelUploadOptions(serialize(job))
                    .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*")),
                    STORAGE_TIMEOUT, Context.NONE);
        } catch (BlobStorageException failure) {
            boolean alreadyExists = failure.getStatusCode() == 409
                    && BlobErrorCode.BLOB_ALREADY_EXISTS.equals(failure.getErrorCode());
            boolean conditionFailed = failure.getStatusCode() == 412
                    && BlobErrorCode.CONDITION_NOT_MET.equals(failure.getErrorCode());
            if (!alreadyExists && !conditionFailed) {
                throw failure;
            }
        }
    }

    @Override
    public void validateLocations(ConversionJobRequest request) {
        profiles.inputConnection(request.input().storage());
        profiles.outputConnection(request.output().storage());
    }

    @Override
    public void enqueue(String message) {
        initialize();
        queue.sendMessage(message);
    }

    @Override
    public Optional<JobRecord> find(String id) {
        try {
            byte[] bytes = readBounded(blob(id, "status.json"), 4L * 1024 * 1024, false, null);
            return Optional.of(mapper.readValue(bytes, JobRecord.class));
        } catch (BlobStorageException failure) {
            if (failure.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw failure;
        } catch (IOException failure) {
            throw new IllegalStateException("The persisted job status could not be read.", failure);
        }
    }

    @Override
    public byte[] readInput(ConversionJobRequest.BlobSource source) {
        try {
            return readBounded(inputBlobs(source.storage()).getBlobContainerClient(source.container()).getBlobClient(source.blobName()),
                    limits.maxInputBytes(), true, null);
        } catch (BlobStorageException failure) {
            if (failure.getStatusCode() == 404) {
                throw new ConversionException(422, "INPUT_NOT_FOUND", "The specified input blob does not exist.", failure);
            }
            throw failure;
        }
    }

    @Override
    public JobRecord.ResultLocation writeResult(ConversionJobRequest request, ConversionResult result) {
        BlobContainerClient output = outputBlobs(request.output().storage()).getBlobContainerClient(request.output().container());
        output.createIfNotExists();
        String prefix = request.output().prefix();
        String root = (prefix.isEmpty() ? "" : prefix + "/") + request.jobId() + "/results/" + UUID.randomUUID() + "/";
        List<JobRecord.Artifact> artifacts = new ArrayList<>();
        if (!result.files().containsKey("document.md") || !result.files().containsKey("report.json")) {
            throw new IllegalStateException("The conversion is missing required artifacts.");
        }
        if ((long) result.files().size() > (long) limits.maxImages() + limits.maxShapes() + 2) {
            throw new ConversionException(413, "ARTIFACT_LIMIT_EXCEEDED", "The result contains too many artifacts.");
        }
        long total = 0;
        try {
            Path directory = result.directory().toRealPath();
            for (Map.Entry<String, Path> entry : result.files().entrySet()) {
                String relative = entry.getKey();
                validateArtifact(relative);
                Path file = entry.getValue();
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().startsWith(directory)) {
                    throw new IllegalStateException("An artifact is outside the conversion workspace.");
                }
                long size = Files.size(file);
                long maximum = relative.equals("document.md") ? limits.maxMarkdownBytes()
                        : relative.startsWith("images/") ? limits.maxImageBytes() : limits.maxOutputBytes();
                checkSize(size, maximum, false);
                total = Math.addExact(total, size);
                checkSize(total, limits.maxOutputBytes(), false);
                String type = contentType(relative);
                String name = root + relative;
                try (InputStream stream = Files.newInputStream(file)) {
                    String etag = output.getBlobClient(name).uploadWithResponse(new BlobParallelUploadOptions(stream, size)
                                    .setParallelTransferOptions(new ParallelTransferOptions().setMaxConcurrency(1)
                                            .setBlockSizeLong(4L * 1024 * 1024).setMaxSingleUploadSizeLong(4L * 1024 * 1024))
                                    .setHeaders(new BlobHttpHeaders().setContentType(type))
                                    .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*")),
                            STORAGE_TIMEOUT, Context.NONE).getValue().getETag();
                    artifacts.add(new JobRecord.Artifact(relative, name, type, size, etag));
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("The result artifacts could not be stored.", failure);
        }
        return new JobRecord.ResultLocation(request.output().storage(), request.output().container(), artifacts,
                result.sheetCount(), result.warningCount());
    }

    @Override
    public JobDownload readResult(JobRecord.ResultLocation result, String artifact) {
        validateArtifact(artifact);
        JobRecord.Artifact selected = result.artifacts().stream().filter(file -> file.path().equals(artifact))
                .findFirst().orElseThrow(() -> new ConversionException(404, "ARTIFACT_NOT_FOUND", "The result artifact was not found."));
        return new JobDownload(readArtifact(result, selected), selected.contentType(),
                artifact.substring(artifact.lastIndexOf('/') + 1));
    }

    @Override
    public JobDownload readArchive(JobRecord.ResultLocation result) {
        // Only the completed attempt's explicit artifact list can enter the archive.
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(new OutputLimit(bytes, limits.maxOutputBytes()))) {
            long total = 0;
            for (JobRecord.Artifact artifact : result.artifacts()) {
                validateArtifact(artifact.path());
                total = Math.addExact(total, artifact.sizeBytes());
                checkSize(total, limits.maxOutputBytes(), false);
                ZipEntry entry = new ZipEntry(artifact.path());
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(readArtifact(result, artifact));
                zip.closeEntry();
            }
            zip.finish();
            return new JobDownload(bytes.toByteArray(), "application/zip", "document.zip");
        } catch (IOException failure) {
            throw new IllegalStateException("The result archive could not be created.", failure);
        }
    }

    private byte[] readArtifact(JobRecord.ResultLocation result, JobRecord.Artifact artifact) {
        if (artifact.eTag() == null || artifact.blobName() == null) {
            throw new IllegalStateException("The result artifact metadata is incomplete.");
        }
        long maximum = artifact.path().equals("document.md") ? limits.maxMarkdownBytes()
                : artifact.path().startsWith("images/") ? limits.maxImageBytes() : limits.maxOutputBytes();
        checkSize(artifact.sizeBytes(), maximum, false);
        BlobClient blob = outputBlobs(result.storage()).getBlobContainerClient(result.container()).getBlobClient(artifact.blobName());
        byte[] bytes = readBounded(blob, Math.min(maximum, limits.maxOutputBytes()), false, artifact.eTag());
        if (bytes.length != artifact.sizeBytes()) throw new IllegalStateException("The result artifact size has changed.");
        return bytes;
    }

    static void validateArtifact(String name) {
        if ("document.md".equals(name) || "report.json".equals(name)) return;
        if (name == null || !name.matches("images/[A-Za-z0-9][A-Za-z0-9._-]{0,199}") || name.contains("..")) {
            throw new ConversionException(404, "ARTIFACT_NOT_FOUND", "The result artifact was not found.");
        }
    }

    private static String contentType(String path) {
        if (path.equals("document.md")) return "text/markdown; charset=utf-8";
        if (path.equals("report.json")) return "application/json; charset=utf-8";
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp") || lower.endsWith(".dib")) return "image/bmp";
        if (lower.endsWith(".wmf")) return "image/wmf";
        if (lower.endsWith(".emf")) return "image/emf";
        return "application/octet-stream";
    }

    private static final class OutputLimit extends FilterOutputStream {
        private final long maximum;
        private long count;
        OutputLimit(OutputStream stream, long maximum) { super(stream); this.maximum = maximum; }
        @Override public void write(int value) throws IOException {
            checkSize(Math.addExact(count, 1), maximum, false);
            out.write(value); count++;
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            checkSize(Math.addExact(count, length), maximum, false);
            out.write(bytes, offset, length); count += length;
        }
    }

    private BlobServiceClient inputBlobs(String alias) {
        return inputClients.computeIfAbsent(alias, key -> configuredClient(profiles.inputConnection(key)));
    }

    private BlobServiceClient outputBlobs(String alias) {
        return outputClients.computeIfAbsent(alias, key -> configuredClient(profiles.outputConnection(key)));
    }

    private static BlobServiceClient configuredClient(String connection) {
        try {
            return new BlobServiceClientBuilder().connectionString(connection).buildClient();
        } catch (IllegalArgumentException failure) {
            // Connection strings contain credentials; never propagate an SDK parsing error with its input.
            throw new IllegalStateException("A configured blob storage connection is invalid.");
        }
    }

    private byte[] readBounded(BlobClient source, long maximum, boolean input, String expectedETag) {
        // Check the length before allocating and pin the download to this ETag so a producer
        // cannot replace a small blob with a large one between the HEAD and GET requests.
        BlobProperties properties = source.getPropertiesWithResponse(null, STORAGE_TIMEOUT, Context.NONE).getValue();
        checkSize(properties.getBlobSize(), maximum, input);
        if (expectedETag != null && !expectedETag.equals(properties.getETag())) {
            throw new ConversionException(409, "RESULT_CHANGED", "The stored result artifact has changed.");
        }
        byte[] bytes = new byte[(int) properties.getBlobSize()];
        int blockSize = (int) Math.max(1, Math.min(4L * 1024 * 1024, properties.getBlobSize()));
        BlobInputStreamOptions options = new BlobInputStreamOptions().setBlockSize(blockSize)
                .setRequestConditions(new BlobRequestConditions().setIfMatch(properties.getETag()));
        try (InputStream stream = source.openInputStream(options)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = stream.read(bytes, offset, bytes.length - offset);
                if (count < 0) {
                    throw new IOException("The blob ended before its declared length.");
                }
                offset += count;
            }
            if (stream.read() != -1) {
                throw new IOException("The blob exceeded its declared length.");
            }
            return bytes;
        } catch (IOException failure) {
            throw new IllegalStateException("The blob could not be downloaded completely.", failure);
        }
    }

    private static void checkSize(long size, long maximum, boolean input) {
        if (size < 0 || size > maximum || size > Integer.MAX_VALUE - 8L) {
            throw new ConversionException(413, input ? "INPUT_LIMIT_EXCEEDED" : "OUTPUT_LIMIT_EXCEEDED",
                    input ? "The document exceeds the configured input size limit."
                            : "The result exceeds the configured output size limit.");
        }
    }

    @Override
    public JobLock lock(String id) {
        BlobClient statusBlob = blob(id, "status.json");
        BlobLeaseClient lease = new BlobLeaseClientBuilder().blobClient(statusBlob).buildClient();
        lease.acquireLease(60);
        return new RenewableLease(statusBlob, lease);
    }

    private synchronized void initialize() {
        if (!initialized) {
            // Default container access is private; neither input nor result uses public URLs.
            container.createIfNotExists();
            queue.createIfNotExists();
            initialized = true;
        }
    }

    private BlobClient blob(String id, String suffix) {
        return container.getBlobClient(id + "/" + suffix);
    }

    private BinaryData serialize(JobRecord job) {
        try {
            return BinaryData.fromBytes(mapper.writeValueAsBytes(job));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("The job status could not be serialized.", failure);
        }
    }

    private final class RenewableLease implements JobLock {
        private final BlobClient statusBlob;
        private final BlobLeaseClient lease;
        private final ScheduledExecutorService renewal;
        private final AtomicReference<RuntimeException> renewalFailure = new AtomicReference<>();

        RenewableLease(BlobClient statusBlob, BlobLeaseClient lease) {
            this.statusBlob = statusBlob;
            this.lease = lease;
            renewal = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "excel2md-job-lease");
                thread.setDaemon(true);
                return thread;
            });
            renewal.scheduleWithFixedDelay(() -> {
                try {
                    lease.renewLeaseWithResponse((RequestConditions) null, STORAGE_TIMEOUT, Context.NONE);
                } catch (RuntimeException failure) {
                    renewalFailure.compareAndSet(null, failure);
                }
            }, 20, 20, TimeUnit.SECONDS);
        }

        @Override
        public void update(JobRecord job) {
            RuntimeException failed = renewalFailure.get();
            if (failed != null) {
                throw new IllegalStateException("The job processing lease could not be renewed.", failed);
            }
            // Azure validates the lease atomically with the write, fencing out a stale worker.
            statusBlob.uploadWithResponse(new BlobParallelUploadOptions(serialize(job))
                    .setRequestConditions(new BlobRequestConditions().setLeaseId(lease.getLeaseId())),
                    STORAGE_TIMEOUT, Context.NONE);
        }

        @Override
        public void close() {
            renewal.shutdownNow();
            try {
                lease.releaseLeaseWithResponse((RequestConditions) null, STORAGE_TIMEOUT, Context.NONE);
            } catch (RuntimeException ignored) {
                // The finite lease expires by itself; never mask the processing failure.
            }
        }
    }
}
