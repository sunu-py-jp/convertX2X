package com.convertx2x.office2md.jobs;

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
import com.convertx2x.office2md.conversion.ConversionException;
import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionResult;
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
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.models.DeleteSnapshotsOptionType;
import java.util.function.Consumer;
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
    private final IntegrationSettings settings;
    private final Map<String, QueueClient> resultQueues = new ConcurrentHashMap<>();
    private static final String OWNER_KEY = "convertx2xowner";
    private static final String MAINTENANCE_INDEX = ".maintenance/jobs/";
    record Ownership(String token, String storage, String container, boolean input, List<String> blobNames, Map<String, String> eTags) {
        Ownership(String token, String storage, String container, boolean input, List<String> blobNames) { this(token, storage, container, input, blobNames, Map.of()); }
        Ownership { eTags = eTags == null ? Map.of() : Map.copyOf(eTags); }
    }
    record MaintenancePage(String continuationToken, List<String> pending) { }

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
        this(IntegrationSettings.from(Map.of("CONVERSION_STORAGE_CONNECTION_STRING", connectionString)), limits, profiles);
    }

    AzureJobStore(IntegrationSettings settings, ConversionLimits limits, BlobStorageProfiles profiles) {
        if (!settings.storage().configured()) throw new IllegalArgumentException("The conversion storage connection is not configured.");
        this.settings = settings; this.limits = limits; this.profiles = profiles;
        blobs = settings.storage().blobs();
        container = blobs.getBlobContainerClient(AzureJobService.CONTAINER_NAME);
        queue = settings.storage().queue(AzureJobService.QUEUE_NAME);
    }

    @Override
    public void create(JobRecord job, byte[] input) {
        validateLocations(job.request());
        initialize();
        checkSize(input.length, limits.maxInputBytes(), true);
        ConversionJobRequest.BlobSource source = job.request().input();
        BlobContainerClient inputContainer = inputBlobs(source.storage()).getBlobContainerClient(source.container());
        if (settings.createResources()) inputContainer.createIfNotExists();
        // The service has persisted submitting state and holds its lease. Record exact ownership before any input bytes.
        String token = UUID.randomUUID().toString();
        recordOwnership(job.job().id(), new Ownership(token, source.storage(), source.container(), true, List.of(source.blobName())));
        String eTag = inputContainer.getBlobClient(source.blobName()).uploadWithResponse(new BlobParallelUploadOptions(BinaryData.fromBytes(input))
                .setMetadata(Map.of(OWNER_KEY, token)).setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*")), STORAGE_TIMEOUT, Context.NONE).getValue().getETag();
        updateOwnership(job.job().id(), new Ownership(token, source.storage(), source.container(), true, List.of(source.blobName()), Map.of(source.blobName(), eTag)));
    }

    @Override
    public void ensure(JobRecord job) {
        initialize();
        // Every new job is indexed, including when maintenance is currently off, so later opt-in can discover it.
        BlobClient index = container.getBlobClient(MAINTENANCE_INDEX + job.job().id() + ".json");
        // Refresh before status creation. A stale marker's conditional cleanup cannot erase a concurrently reused job ID.
        index.uploadWithResponse(new BlobParallelUploadOptions(BinaryData.fromString("{\"version\":1}")), STORAGE_TIMEOUT, Context.NONE);
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
        if (request.notification() != null) settings.resultQueue(request.notification().queue());
    }

    @Override
    public void enqueue(String message) {
        initialize();
        queue.sendMessageWithResponse(message, null, null, STORAGE_TIMEOUT, Context.NONE);
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

    @Override public byte[] readInput(ConversionJobRequest.BlobSource source) { return readInputVersioned(source).bytes(); }

    @Override public InputData readInputVersioned(ConversionJobRequest.BlobSource source) {
        try {
            BlobClient input = inputBlobs(source.storage()).getBlobContainerClient(source.container()).getBlobClient(source.blobName());
            BlobProperties properties = input.getPropertiesWithResponse(null, STORAGE_TIMEOUT, Context.NONE).getValue();
            if (source.expectedETag() != null && !source.expectedETag().equals(properties.getETag())) throw inputVersionMismatch();
            return new InputData(readBounded(input, limits.maxInputBytes(), true, properties.getETag()), properties.getETag());
        } catch (BlobStorageException failure) {
            if (failure.getStatusCode() == 404) throw new ConversionException(422, "INPUT_NOT_FOUND", "The specified input blob does not exist.");
            if (failure.getStatusCode() == 412) throw inputVersionMismatch();
            throw failure;
        }
    }
    private static ConversionException inputVersionMismatch() {
        return new ConversionException(409, "INPUT_VERSION_MISMATCH", "The input blob does not match the requested version.");
    }

    @Override
    public JobRecord.ResultLocation writeResult(ConversionJobRequest request, ConversionResult result) {
        return writeResult(request, result, null);
    }

    @Override
    public JobRecord.ResultLocation writeResult(ConversionJobRequest request, ConversionResult result, String inputETag) {
        BlobContainerClient output = outputBlobs(request.output().storage()).getBlobContainerClient(request.output().container());
        if (settings.createResources()) output.createIfNotExists();
        String prefix = request.output().prefix();
        String root = (prefix.isEmpty() ? "" : prefix + "/") + request.jobId() + "/results/" + UUID.randomUUID() + "/";
        List<JobRecord.Artifact> artifacts = new ArrayList<>();
        if (!result.files().containsKey("document.md") || !result.files().containsKey("report.json")) {
            throw new IllegalStateException("The conversion is missing required artifacts.");
        }
        if ((long) result.files().size() > (long) limits.maxImages() + limits.maxShapes() + 2) {
            throw new ConversionException(413, "ARTIFACT_LIMIT_EXCEEDED", "The result contains too many artifacts.");
        }
        String token = UUID.randomUUID().toString();
        List<String> ownedNames = new ArrayList<>();
        for (String relative : result.files().keySet()) { validateArtifact(relative); ownedNames.add(root + relative); }
        ownedNames.add(root + "manifest.json");
        recordOwnership(request.jobId(), new Ownership(token, request.output().storage(), request.output().container(), false, ownedNames));
        Map<String, String> ownedETags = new LinkedHashMap<>();
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
                                    .setHeaders(new BlobHttpHeaders().setContentType(type)).setMetadata(Map.of(OWNER_KEY, token))
                                    .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*")),
                            STORAGE_TIMEOUT, Context.NONE).getValue().getETag();
                    ownedETags.put(name, etag);
                    updateOwnership(request.jobId(), new Ownership(token, request.output().storage(), request.output().container(), false, ownedNames, ownedETags));
                    artifacts.add(new JobRecord.Artifact(relative, name, type, size, etag, sha256(file)));
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("The result artifacts could not be stored.", failure);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", 1); manifest.put("jobId", request.jobId()); manifest.put("input", sourceInfo(request, inputETag));
        manifest.put("metadata", request.metadata()); manifest.put("sectionCount", result.sectionCount());
        manifest.put("warningCount", result.warningCount()); manifest.put("artifacts", artifacts);
        byte[] manifestBytes = jsonBytes(manifest);
        checkSize(Math.addExact(total, manifestBytes.length), limits.maxOutputBytes(), false);
        String manifestETag = output.getBlobClient(root + "manifest.json").uploadWithResponse(
                new BlobParallelUploadOptions(BinaryData.fromBytes(manifestBytes))
                        .setHeaders(new BlobHttpHeaders().setContentType("application/json"))
                        .setMetadata(Map.of(OWNER_KEY, token)).setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*")),
                STORAGE_TIMEOUT, Context.NONE).getValue().getETag();
        ownedETags.put(root + "manifest.json", manifestETag);
        updateOwnership(request.jobId(), new Ownership(token, request.output().storage(), request.output().container(), false, ownedNames, ownedETags));
        var manifestArtifact = new JobRecord.Artifact("manifest.json", root + "manifest.json", "application/json", manifestBytes.length, manifestETag, sha256(manifestBytes));
        return new JobRecord.ResultLocation(request.output().storage(), request.output().container(), artifacts,
                result.sectionCount(), result.warningCount(), manifestArtifact);
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

    private void recordOwnership(String id, Ownership ownership) {
        initialize();
        blob(id, "ownership/" + ownership.token() + ".json").uploadWithResponse(
                new BlobParallelUploadOptions(BinaryData.fromBytes(jsonBytes(ownership)))
                        .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*")), STORAGE_TIMEOUT, Context.NONE);
    }

    private void updateOwnership(String id, Ownership ownership) {
        blob(id, "ownership/" + ownership.token() + ".json").upload(BinaryData.fromBytes(jsonBytes(ownership)), true);
    }

    static Map<String, Object> sourceInfo(ConversionJobRequest request, String eTag) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("storage", request.input().storage()); source.put("container", request.input().container());
        source.put("blobName", request.input().blobName()); source.put("eTag", eTag);
        return source;
    }

    static Map<String, Object> notificationEvent(JobRecord record) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("version", 1); event.put("eventId", record.job().id() + ":" + record.job().status());
        event.put("jobId", record.job().id()); event.put("status", record.job().status());
        event.put("input", sourceInfo(record.request(), record.inputETag())); event.put("metadata", record.request().metadata());
        if (record.result() != null) {
            var result = record.result();
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("storage", result.storage()); descriptor.put("container", result.container());
            descriptor.put("sectionCount", result.sectionCount()); descriptor.put("warningCount", result.warningCount());
            descriptor.put("manifest", result.manifest()); event.put("result", descriptor);
        } else event.put("result", null);
        event.put("error", record.job().errorCode() == null ? null : Map.of("code", record.job().errorCode(), "retryable", false));
        event.put("completedAt", record.job().updatedAt());
        return event;
    }

    @Override public void notifyResult(JobRecord record) {
        var target = settings.resultQueue(record.request().notification().queue());
        QueueClient client = resultQueues.computeIfAbsent(record.request().notification().queue(), key -> target.storage().queue(target.queueName()));
        if (settings.createResources()) client.createIfNotExistsWithResponse(null, STORAGE_TIMEOUT, Context.NONE);
        byte[] body = jsonBytes(notificationEvent(record));
        if (body.length > 48 * 1024) throw new IllegalStateException("The result event exceeds the queue message limit.");
        client.sendMessageWithResponse(new String(body, java.nio.charset.StandardCharsets.UTF_8), null, null, STORAGE_TIMEOUT, Context.NONE);
    }

    /** One durable cursor, one renewable sweep lease, and per-job leases bound each scan and resume after crashes. */
    @Override public void maintenance(Consumer<String> action) {
        initialize();
        BlobClient state = container.getBlobClient(".maintenance/state.json");
        try { state.uploadWithResponse(new BlobParallelUploadOptions(BinaryData.fromBytes(jsonBytes(new MaintenancePage(null, List.of()))))
                .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*")), STORAGE_TIMEOUT, Context.NONE); }
        catch (BlobStorageException failure) { if (failure.getStatusCode() != 409 && failure.getStatusCode() != 412) throw failure; }
        BlobLeaseClient lease = new BlobLeaseClientBuilder().blobClient(state).buildClient();
        try { lease.acquireLease(60); } catch (BlobStorageException busy) { if (busy.getStatusCode() == 409) return; throw busy; }
        try (RenewableLease guard = new RenewableLease(state, lease)) {
            MaintenancePage page;
            try { page = mapper.readValue(readBounded(state, 256 * 1024, false, null), MaintenancePage.class); }
            catch (IOException failure) { throw new IllegalStateException("The maintenance cursor could not be read."); }
            if (page.pending().isEmpty()) {
                var iterator = container.listBlobs(new ListBlobsOptions().setPrefix(MAINTENANCE_INDEX).setMaxResultsPerPage(100), STORAGE_TIMEOUT)
                        .iterableByPage(page.continuationToken(), 100).iterator();
                if (!iterator.hasNext()) page = new MaintenancePage(null, List.of());
                else {
                    var next = iterator.next();
                    List<String> ids = next.getValue().stream().map(item -> item.getName())
                            .filter(name -> name.matches("\\.maintenance/jobs/[0-9a-f-]{36}\\.json"))
                            .map(name -> name.substring(MAINTENANCE_INDEX.length(), MAINTENANCE_INDEX.length() + 36)).toList();
                    page = new MaintenancePage(next.getContinuationToken(), ids);
                }
                guard.write(BinaryData.fromBytes(jsonBytes(page)));
            }
            List<String> pending = new ArrayList<>(page.pending());
            long deadline = System.nanoTime() + Duration.ofSeconds(150).toNanos();
            while (!pending.isEmpty() && System.nanoTime() < deadline) {
                String id = pending.removeFirst();
                try { action.accept(id); } catch (RuntimeException failure) {
                    // Keep a newly-created index during status creation; stale index-only failures own no data.
                    if ((failure instanceof ConversionException converted && converted.statusCode() == 404
                            || failure instanceof BlobStorageException missing && missing.getStatusCode() == 404) && find(id).isEmpty()) {
                        BlobClient marker = container.getBlobClient(MAINTENANCE_INDEX + id + ".json");
                        try {
                            BlobProperties props = marker.getProperties();
                            if (props.getLastModified().toInstant().isBefore(Instant.now().minus(Duration.ofDays(1))))
                                marker.deleteWithResponse(DeleteSnapshotsOptionType.INCLUDE,
                                        new BlobRequestConditions().setIfMatch(props.getETag()), STORAGE_TIMEOUT, Context.NONE);
                        } catch (RuntimeException ignored) { }
                    }
                    // Other active jobs or failed notification/deletion retry on the next complete scan. Never log payloads.
                }
                guard.write(BinaryData.fromBytes(jsonBytes(new MaintenancePage(page.continuationToken(), pending))));
            }
        }
    }

    @Override public void cleanup(JobRecord record, JobLock lock, Instant now) {
        // Legacy state lacks a complete ownership history. Replay may index it, but cannot safely grant deletion authority.
        if (record.artifactTrackingVersion() != 1 || settings.resultRetentionDays() == 0 || record.pendingNotification()) return;
        Instant completed = Instant.parse(record.job().updatedAt());
        if (record.notificationSentAt() != null && Instant.parse(record.notificationSentAt()).isAfter(completed))
            completed = Instant.parse(record.notificationSentAt());
        if (now.isBefore(completed.plus(Duration.ofDays(settings.resultRetentionDays())))) return;
        if (record.submissionPending() && "queued".equals(record.job().status())) {
            JobStatus old = record.job();
            record = new JobRecord(new JobStatus(old.id(), "failed", old.filename(), old.createdAt(), old.updatedAt(), null, null,
                    "SUBMISSION_EXPIRED", "The asynchronous submission was abandoned."), null, record.request(), null, null, null);
            lock.update(record);
        }
        if (!("succeeded".equals(record.job().status()) || "failed".equals(record.job().status()))) return;
        // Mark expiry before deletion: an interrupted cleanup cannot present a partial result as downloadable.
        if (record.artifactsExpiredAt() == null) { record = record.withExpiredArtifacts(now.toString()); lock.update(record); }
        int visited = 0, remainingDeletes = 100;
        for (var item : container.listBlobs(new ListBlobsOptions().setPrefix(record.job().id() + "/ownership/"), STORAGE_TIMEOUT)) {
            if (++visited > 100) return; // Retain state until every ledger has been visited in later sweeps.
            BlobClient ledger = container.getBlobClient(item.getName());
            Ownership ownership;
            try { ownership = mapper.readValue(readBounded(ledger, 4 * 1024 * 1024, false, null), Ownership.class); }
            catch (IOException failure) { throw new IllegalStateException("The ownership ledger could not be read."); }
            // Ledgers are created only by this app. Validate their token/path before any caller-account access.
            if (!item.getName().equals(record.job().id() + "/ownership/" + ownership.token() + ".json")
                    || !ownership.token().matches("[0-9a-f-]{36}") || !ownedLocation(record.request(), ownership))
                throw new IllegalStateException("The ownership ledger is invalid.");
            BlobContainerClient owned = (ownership.input() ? inputBlobs(ownership.storage()) : outputBlobs(ownership.storage()))
                    .getBlobContainerClient(ownership.container());
            for (int index = 0; index < ownership.blobNames().size(); index++) {
                if (remainingDeletes-- <= 0) {
                    Ownership remaining = new Ownership(ownership.token(), ownership.storage(), ownership.container(), ownership.input(),
                            ownership.blobNames().subList(index, ownership.blobNames().size()), ownership.eTags());
                    ledger.upload(BinaryData.fromBytes(jsonBytes(remaining)), true);
                    return;
                }
                String name = ownership.blobNames().get(index);
                BlobClient artifact = owned.getBlobClient(name);
                try {
                    BlobProperties props = artifact.getPropertiesWithResponse(null, STORAGE_TIMEOUT, Context.NONE).getValue();
                    String expectedETag = ownership.eTags().get(name);
                    if (ownership.token().equals(props.getMetadata().get(OWNER_KEY)) && (expectedETag == null || expectedETag.equals(props.getETag()))) {
                        artifact.deleteWithResponse(DeleteSnapshotsOptionType.INCLUDE,
                                new BlobRequestConditions().setIfMatch(props.getETag()), STORAGE_TIMEOUT, Context.NONE);
                    }
                } catch (BlobStorageException failure) { if (failure.getStatusCode() != 404) throw failure; }
            }
            ledger.deleteWithResponse(DeleteSnapshotsOptionType.INCLUDE, null, STORAGE_TIMEOUT, Context.NONE);
        }
        if (settings.stateRetentionDays() > 0 && !now.isBefore(completed.plus(Duration.ofDays(settings.stateRetentionDays())))) {
            lock.delete(); // Keep the index until the 24-hour missing-status sweep, protecting concurrent job ID reuse.
        }
    }

    private static boolean ownedLocation(ConversionJobRequest request, Ownership ownership) {
        if (ownership.blobNames() == null) return false;
        if (ownership.input()) return "default".equals(ownership.storage()) && AzureJobService.CONTAINER_NAME.equals(ownership.container())
                && ownership.blobNames().stream().allMatch(name -> (request.jobId() + "/input").equals(name));
        if (!request.output().storage().equals(ownership.storage()) || !request.output().container().equals(ownership.container())) return false;
        String prefix = request.output().prefix();
        String root = (prefix.isEmpty() ? "" : prefix + "/") + request.jobId() + "/results/";
        for (String name : ownership.blobNames()) {
            if (name == null || !name.startsWith(root)) return false;
            String rest = name.substring(root.length()); int slash = rest.indexOf('/');
            if (slash != 36 || !rest.substring(0, slash).matches("[0-9a-f-]{36}")) return false;
            String relative = rest.substring(slash + 1);
            if (!relative.equals("manifest.json")) {
                try { validateArtifact(relative); } catch (ConversionException invalid) { return false; }
            }
        }
        return true;
    }

    private byte[] jsonBytes(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Integration metadata could not be serialized."); }
    }
    private static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = digest(); byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) >= 0;) if (count > 0) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        }
    }
    private static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private BlobServiceClient inputBlobs(String alias) {
        return inputClients.computeIfAbsent(alias, key -> profiles.input(key).blobs());
    }

    private BlobServiceClient outputBlobs(String alias) {
        return outputClients.computeIfAbsent(alias, key -> profiles.output(key).blobs());
    }

    private byte[] readBounded(BlobClient source, long maximum, boolean input, String expectedETag) {
        // Check the length before allocating and pin the download to this ETag so a producer
        // cannot replace a small blob with a large one between the HEAD and GET requests.
        BlobProperties properties = source.getPropertiesWithResponse(null, STORAGE_TIMEOUT, Context.NONE).getValue();
        checkSize(properties.getBlobSize(), maximum, input);
        if (expectedETag != null && !expectedETag.equals(properties.getETag())) {
            if (input) throw inputVersionMismatch();
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
            if (settings.createResources()) { container.createIfNotExists(); queue.createIfNotExistsWithResponse(null, STORAGE_TIMEOUT, Context.NONE); }
            initialized = true;
        }
    }

    private BlobClient blob(String id, String suffix) {
        return container.getBlobClient(id + "/" + suffix);
    }

    private BinaryData serialize(JobRecord job) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(job);
            if (bytes.length > 4 * 1024 * 1024) throw new ConversionException(413, "RESULT_METADATA_LIMIT_EXCEEDED", "The job metadata exceeds the storage metadata limit.");
            return BinaryData.fromBytes(bytes);
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
                Thread thread = new Thread(task, "office2md-job-lease");
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

        @Override public void delete() {
            if (renewalFailure.get() != null) throw new IllegalStateException("The maintenance lease was lost.");
            statusBlob.deleteWithResponse(DeleteSnapshotsOptionType.INCLUDE, new BlobRequestConditions().setLeaseId(lease.getLeaseId()), STORAGE_TIMEOUT, Context.NONE);
        }

        void write(BinaryData data) {
            if (renewalFailure.get() != null) throw new IllegalStateException("The maintenance lease was lost.");
            statusBlob.uploadWithResponse(new BlobParallelUploadOptions(data).setRequestConditions(new BlobRequestConditions().setLeaseId(lease.getLeaseId())), STORAGE_TIMEOUT, Context.NONE);
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
