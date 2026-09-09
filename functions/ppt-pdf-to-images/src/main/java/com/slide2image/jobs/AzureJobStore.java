package com.slide2image.jobs;

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
import com.slide2image.conversion.ConversionException;
import com.slide2image.conversion.ConversionLimits;
import com.slide2image.conversion.ConversionResult;
import java.io.IOException;
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
        blobs = new BlobServiceClientBuilder().connectionString(connectionString).buildClient();
        container = blobs.getBlobContainerClient(AzureJobService.CONTAINER_NAME);
        queue = new QueueClientBuilder().connectionString(connectionString)
                .queueName(AzureJobService.QUEUE_NAME)
                .messageEncoding(QueueMessageEncoding.BASE64).buildClient();
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
            byte[] bytes = blob(id, "status.json").downloadContent().toBytes();
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
                    limits.maxInputBytes(), true);
        } catch (BlobStorageException failure) {
            if (failure.getStatusCode() == 404) {
                throw new ConversionException(422, "INPUT_NOT_FOUND", "The specified input blob does not exist.", failure);
            }
            throw failure;
        }
    }

    @Override
    public JobRecord.ResultLocation writeResult(ConversionJobRequest request, ConversionResult result) {
        checkSize(result.bytes().length, limits.maxOutputBytes(), false);
        String name = resultRoot(request) + result.filename();
        BlobContainerClient outputContainer = outputBlobs(request.output().storage()).getBlobContainerClient(request.output().container());
        // Newly created containers are private; existing container access is owned by the operator.
        outputContainer.createIfNotExists();
        uploadOutput(outputContainer, name, result.bytes(), result.contentType());
        return new JobRecord.ResultLocation(request.output().storage(), request.output().container(), name, result.contentType(),
                result.filename(), result.pageCount());
    }

    @Override
    public ImageOutput beginImages(ConversionJobRequest request) {
        BlobContainerClient outputContainer = outputBlobs(request.output().storage())
                .getBlobContainerClient(request.output().container());
        outputContainer.createIfNotExists();
        return new ImageBatch(request, outputContainer, resultRoot(request));
    }

    private static String resultRoot(ConversionJobRequest request) {
        String prefix = request.output().prefix();
        return (prefix.isEmpty() ? "" : prefix + "/") + request.jobId()
                + "/results/" + UUID.randomUUID() + "/";
    }

    private static void uploadOutput(BlobContainerClient outputContainer, String name, byte[] bytes, String contentType) {
        outputContainer.getBlobClient(name).uploadWithResponse(new BlobParallelUploadOptions(BinaryData.fromBytes(bytes))
                .setHeaders(new BlobHttpHeaders().setContentType(contentType))
                .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*")),
                STORAGE_TIMEOUT, Context.NONE);
    }

    private record ImageLocation(int page, String storage, String container, String blobName,
                                 String contentType, String filename, long sizeBytes) {
    }

    private record ImageManifest(int version, String jobId, String mode, int pageCount, List<ImageLocation> images) {
    }

    /** Holds only page metadata; the synchronous upload completes before the next image is rendered. */
    private final class ImageBatch implements ImageOutput {
        private final ConversionJobRequest request;
        private final BlobContainerClient outputContainer;
        private final String root;
        private final List<ImageLocation> images = new ArrayList<>();
        private long imageBytes;
        private boolean closed;

        private ImageBatch(ConversionJobRequest request, BlobContainerClient outputContainer, String root) {
            this.request = request;
            this.outputContainer = outputContainer;
            this.root = root;
        }

        @Override
        public void writePage(int pageNumber, ConversionResult image) {
            requireOpen();
            try {
                if (pageNumber <= 0 || (!images.isEmpty() && pageNumber <= images.getLast().page())) {
                    throw new IllegalArgumentException("Image pages must be supplied once in ascending order.");
                }
                if (images.size() >= limits.maxPages()) {
                    throw new ConversionException(413, "PAGE_LIMIT_EXCEEDED", "The document exceeds the configured page count limit.");
                }
                checkSize(image.bytes().length, limits.maxOutputBytes(), false);
                checkSize(imageBytes + image.bytes().length, limits.maxOutputBytes(), false);
                String format = request.options().format();
                String contentType = "png".equals(format) ? "image/png" : "image/jpeg";
                if (!contentType.equals(image.contentType())) {
                    throw new IllegalArgumentException("The image content type does not match the requested format.");
                }
                String filename = String.format(Locale.ROOT, "page-%04d.%s", pageNumber, format);
                String name = root + filename;
                uploadOutput(outputContainer, name, image.bytes(), contentType);
                images.add(new ImageLocation(pageNumber, request.output().storage(), request.output().container(),
                        name, contentType, filename, image.bytes().length));
                imageBytes += image.bytes().length;
            } catch (RuntimeException failure) {
                // A partial attempt cannot be finalized after any failed page upload or limit check.
                closed = true;
                throw failure;
            }
        }

        @Override
        public JobRecord.ResultLocation finish() {
            requireOpen();
            closed = true;
            if (images.isEmpty()) {
                throw new IllegalStateException("An image result must contain at least one page.");
            }
            final byte[] manifest;
            try {
                manifest = mapper.writeValueAsBytes(new ImageManifest(1, request.jobId(), "images", images.size(), List.copyOf(images)));
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("The image manifest could not be serialized.", failure);
            }
            checkSize(imageBytes + manifest.length, limits.maxOutputBytes(), false);
            String name = root + "manifest.json";
            uploadOutput(outputContainer, name, manifest, "application/json");
            return new JobRecord.ResultLocation(request.output().storage(), request.output().container(), name,
                    "application/json", "manifest.json", images.size());
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("This image output attempt is already complete or has failed.");
            }
        }
    }

    @Override
    public ConversionResult readResult(JobRecord.ResultLocation result) {
        byte[] bytes = readBounded(outputBlobs(result.storage()).getBlobContainerClient(result.container()).getBlobClient(result.blobName()),
                limits.maxOutputBytes(), false);
        return new ConversionResult(bytes, result.contentType(), result.filename(), result.pageCount());
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

    private byte[] readBounded(BlobClient source, long maximum, boolean input) {
        // Check the length before allocating and pin the download to this ETag so a producer
        // cannot replace a small blob with a large one between the HEAD and GET requests.
        BlobProperties properties = source.getPropertiesWithResponse(null, STORAGE_TIMEOUT, Context.NONE).getValue();
        checkSize(properties.getBlobSize(), maximum, input);
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
        if (size > maximum) {
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
                Thread thread = new Thread(task, "conversion-job-lease");
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
