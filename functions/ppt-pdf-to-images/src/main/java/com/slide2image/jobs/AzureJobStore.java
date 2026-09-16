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
import java.time.Instant;
import java.util.HexFormat;
import java.security.MessageDigest;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.models.DeleteSnapshotsOptionType;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class AzureJobStore implements JobStore {
    private static final Duration STORAGE_TIMEOUT = Duration.ofSeconds(30);
    private final BlobServiceClient blobs;
    private final IntegrationSettings integration;
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
        this(IntegrationSettings.from(Map.of("CONVERSION_STORAGE_CONNECTION_STRING", connectionString == null ? "" : connectionString)), limits, profiles);
    }

    AzureJobStore(IntegrationSettings integration, ConversionLimits limits, BlobStorageProfiles profiles) {
        if (!integration.control().hasBlob() || !integration.control().hasQueue())
            throw new IllegalArgumentException("The conversion storage connection is not configured.");
        this.integration = integration;
        this.limits = limits;
        this.profiles = profiles;
        blobs = integration.control().blobs();
        container = blobs.getBlobContainerClient(AzureJobService.CONTAINER_NAME);
        queue = integration.control().queue(AzureJobService.QUEUE_NAME);
    }

    @Override public void submit(JobRecord job, byte[] input, String message) {
        validateLocations(job.request());
        checkSize(input.length, limits.maxInputBytes(), true);
        ensure(job);
        try (JobLock lock = lock(job.job().id())) {
            uploadInput(job, input);
            enqueue(message);
            lock.update(new JobRecord(job.job(), job.result(), job.request(), job.input(), null, false, false));
        }
    }

    @Override
    public void create(JobRecord job, byte[] input) {
        validateLocations(job.request());
        initialize();
        checkSize(input.length, limits.maxInputBytes(), true);
        ensure(job);
        uploadInput(job, input);
    }

    private void uploadInput(JobRecord job, byte[] input) {
        ConversionJobRequest.BlobSource source = job.request().input();
        BlobContainerClient inputContainer = inputBlobs(source.storage()).getBlobContainerClient(source.container());
        if (integration.createResources()) inputContainer.createIfNotExists();
        uploadOwned(job.request(), "input", source.storage(), source.container(), source.blobName(), input, "application/octet-stream");
    }

    @Override
    public void ensure(JobRecord job) {
        initialize();
        // Publish discovery before state so a crash cannot strand a terminal outbox.
        // Refresh this small owned marker to distinguish creation in progress from old orphan markers.
        container.getBlobClient("_maintenance/jobs/" + job.job().id() + ".json").upload(BinaryData.fromString("{\"version\":1}"), true);
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
        profiles.input(request.input().storage());
        profiles.output(request.output().storage());
        if (request.notification() != null) integration.resultQueue(request.notification().queue());
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

    @Override public byte[] readInput(ConversionJobRequest.BlobSource source) { return readInputData(source).bytes(); }

    @Override public InputData readInputData(ConversionJobRequest.BlobSource source) {
        try {
            BlobClient blob = inputBlobs(source.storage()).getBlobContainerClient(source.container()).getBlobClient(source.blobName());
            BlobProperties properties = blob.getPropertiesWithResponse(null, STORAGE_TIMEOUT, Context.NONE).getValue();
            if (source.expectedETag() != null && !source.expectedETag().equals(properties.getETag())) throw versionMismatch();
            byte[] bytes = readBounded(blob, limits.maxInputBytes(), true, properties);
            return new InputData(bytes, new JobRecord.SourceInfo(source.storage(), source.container(), source.blobName(), properties.getETag()));
        } catch (BlobStorageException failure) {
            if (failure.getStatusCode() == 404)
                throw new ConversionException(422, "INPUT_NOT_FOUND", "The specified input blob does not exist.");
            if (failure.getStatusCode() == 412) throw versionMismatch();
            throw failure;
        }
    }
    private static ConversionException versionMismatch() {
        return new ConversionException(409, "INPUT_VERSION_MISMATCH", "The input blob no longer matches the requested version.");
    }

    @Override
    public JobRecord.ResultLocation writeResult(ConversionJobRequest request, ConversionResult result) {
        checkSize(result.bytes().length, limits.maxOutputBytes(), false);
        String name = resultRoot(request) + result.filename();
        BlobContainerClient outputContainer = outputBlobs(request.output().storage()).getBlobContainerClient(request.output().container());
        // Newly created containers are private; existing container access is owned by the operator.
        if (integration.createResources()) outputContainer.createIfNotExists();
        String eTag = uploadOwned(request, "output", request.output().storage(), request.output().container(), name, result.bytes(), result.contentType());
        return new JobRecord.ResultLocation(request.output().storage(), request.output().container(), name, result.contentType(),
                result.filename(), result.pageCount(), result.bytes().length, sha256(result.bytes()), eTag);
    }

    @Override
    public ImageOutput beginImages(ConversionJobRequest request) { return beginImages(request, null); }

    @Override public ImageOutput beginImages(ConversionJobRequest request, JobRecord.SourceInfo input) {
        BlobContainerClient outputContainer = outputBlobs(request.output().storage())
                .getBlobContainerClient(request.output().container());
        if (integration.createResources()) outputContainer.createIfNotExists();
        return new ImageBatch(request, outputContainer, resultRoot(request), input);
    }

    private static String resultRoot(ConversionJobRequest request) {
        String prefix = request.output().prefix();
        return (prefix.isEmpty() ? "" : prefix + "/") + request.jobId()
                + "/results/" + UUID.randomUUID() + "/";
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable."); }
    }

    /** A write-ahead ledger limits cleanup to exact app-owned objects, including interrupted attempts. */
    private String uploadOwned(ConversionJobRequest request, String role, String storage, String containerName,
                               String name, byte[] bytes, String contentType) {
        initialize();
        String token = UUID.randomUUID().toString();
        OwnedArtifact artifact = new OwnedArtifact(role, storage, containerName, name, token, null);
        BlobClient ledger = container.getBlobClient("_owned/" + request.jobId() + "/" + token + ".json");
        ledger.upload(json(artifact), false);
        BlobClient destination = (role.equals("input") ? inputBlobs(storage) : outputBlobs(storage))
                .getBlobContainerClient(containerName).getBlobClient(name);
        String eTag = destination.uploadWithResponse(new BlobParallelUploadOptions(BinaryData.fromBytes(bytes))
                .setHeaders(new BlobHttpHeaders().setContentType(contentType))
                .setMetadata(Map.of("conversion_owner", token))
                .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*")), STORAGE_TIMEOUT, Context.NONE).getValue().getETag();
        ledger.upload(json(new OwnedArtifact(role, storage, containerName, name, token, eTag)), true);
        return eTag;
    }
    private record OwnedArtifact(String role, String storage, String container, String blobName, String token, String eTag) {}
    private BinaryData json(Object value) {
        try { return BinaryData.fromBytes(mapper.writeValueAsBytes(value)); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Storage metadata serialization failed."); }
    }

    private record ImageLocation(int page, String storage, String container, String blobName,
                                 String contentType, String filename, long sizeBytes, String sha256) {
    }

    private record ImageManifest(int version, String jobId, String mode, int pageCount, List<ImageLocation> images,
                                 JobRecord.SourceInfo input, Map<String, String> metadata) {
    }

    /** Holds only page metadata; the synchronous upload completes before the next image is rendered. */
    private final class ImageBatch implements ImageOutput {
        private final ConversionJobRequest request;
        private final BlobContainerClient outputContainer;
        private final String root;
        private final JobRecord.SourceInfo input;
        private final List<ImageLocation> images = new ArrayList<>();
        private long imageBytes;
        private boolean closed;

        private ImageBatch(ConversionJobRequest request, BlobContainerClient outputContainer, String root, JobRecord.SourceInfo input) {
            this.input = input;
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
                String filename = request.output().naming().equals("page-number") ? pageNumber + "." + format
                        : String.format(Locale.ROOT, "page-%04d.%s", pageNumber, format);
                String name = root + filename;
                uploadOwned(request, "output", request.output().storage(), request.output().container(), name, image.bytes(), contentType);
                images.add(new ImageLocation(pageNumber, request.output().storage(), request.output().container(),
                        name, contentType, filename, image.bytes().length, sha256(image.bytes())));
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
                manifest = mapper.writeValueAsBytes(new ImageManifest(request.version(), request.jobId(), "images", images.size(), List.copyOf(images), input, request.metadata()));
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("The image manifest could not be serialized.", failure);
            }
            checkSize(imageBytes + manifest.length, limits.maxOutputBytes(), false);
            String name = root + "manifest.json";
            String eTag = uploadOwned(request, "output", request.output().storage(), request.output().container(), name, manifest, "application/json");
            return new JobRecord.ResultLocation(request.output().storage(), request.output().container(), name,
                    "application/json", "manifest.json", images.size(), manifest.length, sha256(manifest), eTag);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("This image output attempt is already complete or has failed.");
            }
        }
    }

    @Override
    public ConversionResult readResult(JobRecord.ResultLocation result) {
        BlobClient blob = outputBlobs(result.storage()).getBlobContainerClient(result.container()).getBlobClient(result.blobName());
        BlobProperties properties = blob.getProperties();
        if (result.eTag() != null && !result.eTag().equals(properties.getETag()))
            throw new ConversionException(409, "RESULT_VERSION_MISMATCH", "The stored result has been replaced.");
        byte[] bytes = readBounded(blob, limits.maxOutputBytes(), false, properties);
        return new ConversionResult(bytes, result.contentType(), result.filename(), result.pageCount());
    }

    private BlobServiceClient inputBlobs(String alias) {
        return inputClients.computeIfAbsent(alias, key -> profiles.input(key).blobs());
    }

    private BlobServiceClient outputBlobs(String alias) {
        return outputClients.computeIfAbsent(alias, key -> profiles.output(key).blobs());
    }

    private byte[] readBounded(BlobClient source, long maximum, boolean input) {
        // Check the length before allocating and pin the download to this ETag so a producer
        // cannot replace a small blob with a large one between the HEAD and GET requests.
        BlobProperties properties = source.getPropertiesWithResponse(null, STORAGE_TIMEOUT, Context.NONE).getValue();
        return readBounded(source, maximum, input, properties);
    }

    private byte[] readBounded(BlobClient source, long maximum, boolean input, BlobProperties properties) {
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

    @Override public void sendNotification(JobRecord record) {
        IntegrationSettings.QueueDestination destination = integration.resultQueue(record.request().notification().queue());
        QueueClient resultQueue = destination.connection().queue(destination.name());
        if (integration.createResources()) resultQueue.createIfNotExistsWithResponse(null, STORAGE_TIMEOUT, Context.NONE);
        String message = json(record.notificationEvent()).toString();
        if (message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > ConversionJobRequest.MAX_MESSAGE_BYTES)
            throw new IllegalStateException("The result event exceeds the supported queue message size.");
        resultQueue.sendMessageWithResponse(message, null, null, STORAGE_TIMEOUT, Context.NONE);
    }

    /** One bounded page per invocation, coordinated across scaled instances with a renewable lease. */
    @Override public void maintain() { maintain(Instant.now()); }

    void maintain(Instant now) {
        initialize();
        BlobClient cursorBlob = container.getBlobClient("_maintenance/cursor.json");
        try { cursorBlob.upload(BinaryData.fromString("{}"), false); }
        catch (BlobStorageException failure) { if (failure.getStatusCode() != 409) throw failure; }
        BlobLeaseClient lease = new BlobLeaseClientBuilder().blobClient(cursorBlob).buildClient();
        try { lease.acquireLease(60); }
        catch (BlobStorageException failure) { if (failure.getStatusCode() == 409) return; throw failure; }
        try (RenewableLease cursorLock = new RenewableLease(cursorBlob, lease)) {
            String token;
            String after;
            try { var cursor = mapper.readTree(cursorBlob.downloadContent().toBytes());
                token = cursor.path("version").asInt() == 2 ? cursor.path("continuation").textValue() : null;
                if (token != null && token.isEmpty()) token = null;
                after = cursor.path("version").asInt() == 2 ? cursor.path("after").asText("") : ""; }
            catch (IOException failure) { throw new IllegalStateException("Maintenance cursor is invalid."); }
            var pages = container.listBlobs(new ListBlobsOptions().setPrefix("_maintenance/jobs/"), STORAGE_TIMEOUT).iterableByPage(token, 100).iterator();
            if (!pages.hasNext()) { cursorLock.write(BinaryData.fromString("{}")); return; }
            var page = pages.next();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100);
            for (var item : page.getValue()) {
                if (item.getName().compareTo(after) <= 0) continue;
                if (System.nanoTime() > deadline) return; // Resume after the last processed marker, including failed deliveries.
                if (!item.getName().matches("_maintenance/jobs/[0-9a-f-]{36}\\.json")) continue;
                String id = item.getName().substring("_maintenance/jobs/".length(), "_maintenance/jobs/".length() + 36);
                try { maintainJob(id, now, deadline); }
                catch (RuntimeException ignored) { /* A later pass retries; never log stored references or SDK URLs. */ }
                cursorLock.write(json(Map.of("version", 2, "continuation", token == null ? "" : token, "after", item.getName())));
            }
            cursorLock.write(json(Map.of("version", 2, "continuation", page.getContinuationToken() == null ? "" : page.getContinuationToken())));
        }
    }

    private void maintainJob(String id, Instant now, long deadline) {
        if (find(id).isEmpty()) {
            BlobClient marker = container.getBlobClient("_maintenance/jobs/" + id + ".json");
            BlobProperties properties = marker.getProperties();
            if (properties.getLastModified().toInstant().isBefore(now.minus(Duration.ofDays(1))))
                marker.deleteWithResponse(DeleteSnapshotsOptionType.INCLUDE, new BlobRequestConditions().setIfMatch(properties.getETag()), STORAGE_TIMEOUT, Context.NONE);
            return;
        }
        try (JobLock jobLock = lock(id)) {
            JobRecord record = find(id).orElseThrow();
            // Pre-upgrade states lack an ownership ledger: never adopt or expire their artifacts/state.
            if (record.artifactTrackingVersion() != 1) return;
            if (record.submissionPending() && "queued".equals(record.job().status()) && integration.resultRetentionDays() > 0
                    && !now.isBefore(Instant.parse(record.job().createdAt()).plus(Duration.ofDays(integration.resultRetentionDays())))) {
                JobStatus old = record.job();
                JobStatus expired = new JobStatus(old.id(), "failed", old.filename(), old.options(), old.createdAt(), now.toString(),
                        null, "SUBMISSION_EXPIRED", "An incomplete submission exceeded its retention window.");
                record = new JobRecord(expired, null, record.request());
                jobLock.update(record);
            }
            if (!record.terminal()) return; // Protect normal queued/running jobs and all their attempts.
            if (record.notificationPending()) {
                sendNotification(record);
                record = new JobRecord(record.job(), record.result(), record.request(), record.input(), now.toString(), record.resultExpired());
                jobLock.update(record);
            }
            Instant retentionAnchor = Instant.parse(record.job().updatedAt());
            if (record.notificationSentAt() != null && Instant.parse(record.notificationSentAt()).isAfter(retentionAnchor))
                retentionAnchor = Instant.parse(record.notificationSentAt());
            if (integration.resultRetentionDays() == 0 || now.isBefore(retentionAnchor
                    .plus(Duration.ofDays(integration.resultRetentionDays())))) return;
            if (!record.resultExpired()) {
                record = new JobRecord(record.job(), record.result(), record.request(), record.input(), record.notificationSentAt(), true);
                jobLock.update(record);
            }
            boolean empty = cleanupOwned(record, deadline);
            if (empty && integration.stateRetentionDays() > 0 && !now.isBefore(retentionAnchor
                    .plus(Duration.ofDays(integration.stateRetentionDays())))) {
                ((RenewableLease) jobLock).delete();
                // Retain discovery until the missing-state grace period. Immediate deletion can race
                // a producer reusing this ID after the deduplication retention window.
            }
        }
    }

    /** Never enumerate an output prefix: only exact write-ahead entries owned by this job are eligible. */
    private boolean cleanupOwned(JobRecord record, long deadline) {
        String prefix = "_owned/" + record.job().id() + "/";
        var pages = container.listBlobs(new ListBlobsOptions().setPrefix(prefix), STORAGE_TIMEOUT).iterableByPage(100).iterator();
        if (!pages.hasNext()) return true;
        var page = pages.next();
        for (var item : page.getValue()) {
            if (System.nanoTime() > deadline) return false;
            BlobClient ledger = container.getBlobClient(item.getName());
            final OwnedArtifact artifact;
            try { artifact = mapper.readValue(ledger.downloadContent().toBytes(), OwnedArtifact.class); }
            catch (IOException failure) { throw new IllegalStateException("Invalid owned artifact metadata."); }
            if (!ownedLocation(record.request(), artifact) || !item.getName().equals(prefix + artifact.token() + ".json"))
                throw new IllegalStateException("Artifact ownership cannot be verified.");
            BlobClient target = (artifact.role().equals("input") ? inputBlobs(artifact.storage()) : outputBlobs(artifact.storage()))
                    .getBlobContainerClient(artifact.container()).getBlobClient(artifact.blobName());
            try {
                BlobProperties properties = target.getProperties();
                // A caller replacement is not ours, even when it occupies a former owned path.
                if (artifact.token().equals(properties.getMetadata().get("conversion_owner"))
                        && (artifact.eTag() == null || artifact.eTag().equals(properties.getETag()))) {
                    target.deleteWithResponse(DeleteSnapshotsOptionType.INCLUDE,
                            new BlobRequestConditions().setIfMatch(properties.getETag()), STORAGE_TIMEOUT, Context.NONE);
                }
            } catch (BlobStorageException failure) { if (failure.getStatusCode() != 404) throw failure; }
            ledger.deleteIfExists();
        }
        return page.getContinuationToken() == null;
    }

    private static boolean ownedLocation(ConversionJobRequest request, OwnedArtifact artifact) {
        if (artifact.token() == null || !artifact.token().matches("[0-9a-f-]{36}")) return false;
        if ("input".equals(artifact.role())) return "default".equals(artifact.storage())
                && AzureJobService.CONTAINER_NAME.equals(artifact.container())
                && (request.jobId() + "/input").equals(artifact.blobName());
        String prefix = request.output().prefix();
        String root = (prefix.isEmpty() ? "" : prefix + "/") + request.jobId() + "/results/";
        return "output".equals(artifact.role()) && request.output().storage().equals(artifact.storage())
                && request.output().container().equals(artifact.container()) && artifact.blobName().startsWith(root)
                && artifact.blobName().substring(root.length()).matches("[0-9a-f-]{36}/[^/]+")
                && !artifact.blobName().endsWith("/.") && !artifact.blobName().endsWith("/..");
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
            if (integration.createResources()) { container.createIfNotExists(); queue.createIfNotExists(); }
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
        public void update(JobRecord job) { write(serialize(job)); }

        void write(BinaryData data) {
            RuntimeException failed = renewalFailure.get();
            if (failed != null) {
                throw new IllegalStateException("The job processing lease could not be renewed.", failed);
            }
            // Azure validates the lease atomically with the write, fencing out a stale worker.
            statusBlob.uploadWithResponse(new BlobParallelUploadOptions(data)
                    .setRequestConditions(new BlobRequestConditions().setLeaseId(lease.getLeaseId())),
                    STORAGE_TIMEOUT, Context.NONE);
        }

        void delete() {
            if (renewalFailure.get() != null) throw new IllegalStateException("Maintenance lease was lost.");
            statusBlob.deleteWithResponse(DeleteSnapshotsOptionType.INCLUDE,
                    new BlobRequestConditions().setLeaseId(lease.getLeaseId()), STORAGE_TIMEOUT, Context.NONE);
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
