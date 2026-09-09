package com.slide2image.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slide2image.conversion.ConversionException;
import com.slide2image.conversion.ConversionLimits;
import com.slide2image.conversion.ConversionOptions;
import com.slide2image.conversion.ConversionResult;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt in against an isolated test account or Azurite; ordinary tests require no storage. */
@EnabledIfEnvironmentVariable(named = "CONVERSION_TEST_STORAGE_CONNECTION_STRING", matches = ".+")
class AzureJobStoreIntegrationTest {
    @Test
    void persistsBinaryResultsAndFencesStatusWritesWithRealBlobLeases() {
        String connection = System.getenv("CONVERSION_TEST_STORAGE_CONNECTION_STRING");
        AzureJobStore store = new AzureJobStore(connection);
        BlobContainerClient container = new BlobContainerClientBuilder().connectionString(connection)
                .containerName(AzureJobService.CONTAINER_NAME).buildClient();
        String id = UUID.randomUUID().toString();
        JobStatus queued = new JobStatus(id, "queued", "日本語.pdf", new ConversionOptions(800, "png", 1),
                "2026-09-08T00:00:00Z", "2026-09-08T00:00:00Z", null, null, null);
        ConversionJobRequest request = new ConversionJobRequest(1, id,
                new ConversionJobRequest.BlobSource(AzureJobService.CONTAINER_NAME, id + "/input"),
                new ConversionJobRequest.BlobOutput(AzureJobService.CONTAINER_NAME, ""),
                queued.filename(), queued.options()).normalized();
        JobRecord original = new JobRecord(queued, null, request);
        byte[] input = {0, 1, 2, 3, (byte) 255};
        ConversionResult result = new ConversionResult(new byte[]{4, 5, (byte) 254}, "image/png", "日本語.png", 1);
        try {
            store.create(original, input);
            assertEquals(queued, store.find(id).orElseThrow().job());
            assertEquals(request, store.find(id).orElseThrow().request());
            assertArrayEquals(input, store.readInput(request.input()));
            try (JobStore.JobLock lock = store.lock(id)) {
                assertThrows(BlobStorageException.class, () -> store.lock(id));
                // Azure must reject a write that does not carry the current worker's lease.
                assertThrows(BlobStorageException.class, () -> container.getBlobClient(id + "/status.json")
                        .upload(BinaryData.fromString("{}"), true));
                JobRecord.ResultLocation location = store.writeResult(request, result);
                JobStatus succeeded = new JobStatus(id, "succeeded", queued.filename(), queued.options(),
                        queued.createdAt(), "2026-09-08T00:00:01Z", 1, null, null);
                lock.update(new JobRecord(succeeded, location, request));
                // A second queue delivery may arrive while this worker still holds its lease.
                store.ensure(original);
                JobRecord stored = store.find(id).orElseThrow();
                assertEquals(succeeded, stored.job());
                assertEquals(location, stored.result());
                ConversionResult downloaded = store.readResult(stored.result());
                assertArrayEquals(result.bytes(), downloaded.bytes());
                assertEquals(result.filename(), downloaded.filename());
                assertEquals(result.contentType(), downloaded.contentType());
            }
            try (JobStore.JobLock ignored = store.lock(id)) {
                assertEquals("succeeded", store.find(id).orElseThrow().job().status());
            }
        } finally {
            // Delete only this test's UUID prefix, never shared containers or queues.
            for (var blob : container.listBlobs(new ListBlobsOptions().setPrefix(id + "/"), Duration.ofSeconds(30))) {
                container.getBlobClient(blob.getName()).deleteIfExists();
            }
        }
    }

    @Test
    void processesReferencesFromRegisteredStorageAliasesWithoutAnHttpUpload() {
        String connection = System.getenv("CONVERSION_TEST_STORAGE_CONNECTION_STRING");
        String inputConnection = System.getenv().getOrDefault("CONVERSION_TEST_INPUT_STORAGE_CONNECTION_STRING", connection);
        String outputConnection = System.getenv().getOrDefault("CONVERSION_TEST_OUTPUT_STORAGE_CONNECTION_STRING", connection);
        BlobStorageProfiles profiles = BlobStorageProfiles.from(Map.of(
                "CONVERSION_INPUT_STORAGE_SOURCE", inputConnection,
                "CONVERSION_OUTPUT_STORAGE_ARCHIVE", outputConnection), connection);
        AzureJobStore store = new AzureJobStore(connection, ConversionLimits.defaults(), profiles);
        String id = UUID.randomUUID().toString();
        BlobContainerClient input = container(inputConnection, "external-input-" + id);
        BlobContainerClient output = container(outputConnection, "external-output-" + id);
        BlobContainerClient control = container(connection, AzureJobService.CONTAINER_NAME);
        ConversionJobRequest request = new ConversionJobRequest(1, id,
                new ConversionJobRequest.BlobSource("source", input.getBlobContainerName(), "inbox/日本語.pdf"),
                new ConversionJobRequest.BlobOutput("archive", output.getBlobContainerName(), "processed/customer-a"),
                "日本語.pdf", new ConversionOptions(null, "png", null)).normalized();
        JobStatus queued = new JobStatus(id, "queued", request.filename(), request.options(),
                "2026-09-08T00:00:00Z", "2026-09-08T00:00:00Z", null, null, null);
        byte[] uploaded = {1, 2, 3, (byte) 255};
        ConversionResult result = new ConversionResult(new byte[]{4, 5, 6}, "application/zip", "document.zip", 2);
        try {
            input.create();
            input.getBlobClient(request.input().blobName()).upload(BinaryData.fromBytes(uploaded));
            // This mirrors a producer that writes a blob and queue JSON only, without /api/jobs.
            assertTrue(store.find(id).isEmpty());
            store.ensure(new JobRecord(queued, null, request));
            store.validateLocations(request);
            assertEquals(request, store.find(id).orElseThrow().request());
            assertArrayEquals(uploaded, store.readInput(request.input()));
            assertFalse(control.getBlobClient(id + "/input").exists());
            JobRecord.ResultLocation first = store.writeResult(request, result);
            JobRecord.ResultLocation second = store.writeResult(request, result);
            assertEquals("archive", first.storage());
            assertEquals(output.getBlobContainerName(), first.container());
            assertTrue(first.blobName().startsWith("processed/customer-a/" + id + "/results/"));
            assertTrue(first.blobName().endsWith("/document.zip"));
            assertNotEquals(first.blobName(), second.blobName());
            assertEquals("application/zip", output.getBlobClient(first.blobName()).getProperties().getContentType());
            assertNull(output.getAccessPolicy().getBlobAccessType());
            assertArrayEquals(result.bytes(), store.readResult(first).bytes());

            // An input-only alias cannot be used to choose an output destination.
            ConversionJobRequest wrongRole = new ConversionJobRequest(1, id, request.input(),
                    new ConversionJobRequest.BlobOutput("source", output.getBlobContainerName(), ""),
                    request.filename(), request.options());
            assertEquals("UNKNOWN_OUTPUT_STORAGE", assertThrows(ConversionException.class,
                    () -> store.validateLocations(wrongRole)).code());
        } finally {
            input.deleteIfExists();
            output.deleteIfExists();
            deletePrefix(control, id + "/");
        }
    }

    @Test
    void rejectsOversizedExternalBlobsAndMapsMissingInputToTerminalFailure() {
        String connection = System.getenv("CONVERSION_TEST_STORAGE_CONNECTION_STRING");
        AzureJobStore store = new AzureJobStore(connection, new ConversionLimits(5, 50, 1_000_000, 3));
        String id = UUID.randomUUID().toString();
        BlobContainerClient input = container(connection, "bounded-input-" + id);
        try {
            input.create();
            input.getBlobClient("large.pdf").upload(BinaryData.fromBytes(new byte[6]));
            ConversionException oversized = assertThrows(ConversionException.class, () -> store.readInput(
                    new ConversionJobRequest.BlobSource(input.getBlobContainerName(), "large.pdf")));
            assertEquals(413, oversized.statusCode());
            assertEquals("INPUT_LIMIT_EXCEEDED", oversized.code());

            input.getBlobClient("exact.pdf").upload(BinaryData.fromBytes(new byte[5]));
            assertEquals(5, store.readInput(new ConversionJobRequest.BlobSource(input.getBlobContainerName(), "exact.pdf")).length);

            ConversionException missing = assertThrows(ConversionException.class, () -> store.readInput(
                    new ConversionJobRequest.BlobSource(input.getBlobContainerName(), "missing.pdf")));
            assertEquals(422, missing.statusCode());
            assertEquals("INPUT_NOT_FOUND", missing.code());

            input.getBlobClient("too-large.zip").upload(BinaryData.fromBytes(new byte[4]));
            JobRecord.ResultLocation location = new JobRecord.ResultLocation("default", input.getBlobContainerName(),
                    "too-large.zip", "application/zip", "result.zip", 1);
            ConversionException oversizedResult = assertThrows(ConversionException.class, () -> store.readResult(location));
            assertEquals(413, oversizedResult.statusCode());
            assertEquals("OUTPUT_LIMIT_EXCEEDED", oversizedResult.code());
        } finally {
            input.deleteIfExists();
        }
    }

    @Test
    void storesIndividualPagesAndPublishesOneManifestPerAttempt() throws Exception {
        String connection = System.getenv("CONVERSION_TEST_STORAGE_CONNECTION_STRING");
        String outputConnection = System.getenv().getOrDefault("CONVERSION_TEST_OUTPUT_STORAGE_CONNECTION_STRING", connection);
        BlobStorageProfiles profiles = BlobStorageProfiles.from(Map.of(
                "CONVERSION_OUTPUT_STORAGE_ARCHIVE", outputConnection), connection);
        AzureJobStore store = new AzureJobStore(connection, ConversionLimits.defaults(), profiles);
        String id = UUID.randomUUID().toString();
        BlobContainerClient output = container(outputConnection, "image-output-" + id);
        ConversionJobRequest request = imageRequest(id, "archive", output.getBlobContainerName(), "png");
        byte[] firstPage = {1, 2, 3};
        byte[] secondPage = {4, 5, 6, 7};
        try {
            JobStore.ImageOutput batch = store.beginImages(request);
            batch.writePage(1, new ConversionResult(firstPage, "image/png", "ignored.png", 1));
            batch.writePage(2, new ConversionResult(secondPage, "image/png", "ignored.png", 1));
            assertEquals(2, output.listBlobs().stream().count());
            assertTrue(output.listBlobs().stream().noneMatch(blob -> blob.getName().endsWith("manifest.json")));
            JobRecord.ResultLocation location = batch.finish();
            assertEquals("archive", location.storage());
            assertEquals(output.getBlobContainerName(), location.container());
            assertEquals("application/json", location.contentType());
            assertEquals("manifest.json", location.filename());
            assertEquals(2, location.pageCount());
            assertEquals("application/json", output.getBlobClient(location.blobName()).getProperties().getContentType());
            String root = location.blobName().substring(0, location.blobName().lastIndexOf('/') + 1);
            JsonNode manifest = new ObjectMapper().readTree(store.readResult(location).bytes());
            assertEquals(1, manifest.get("version").intValue());
            assertEquals(id, manifest.get("jobId").textValue());
            assertEquals("images", manifest.get("mode").textValue());
            assertEquals(2, manifest.get("pageCount").intValue());
            assertEquals(2, manifest.get("images").size());
            for (int i = 0; i < 2; i++) {
                JsonNode entry = manifest.get("images").get(i);
                String filename = "page-000" + (i + 1) + ".png";
                assertEquals(i + 1, entry.get("page").intValue());
                assertEquals("archive", entry.get("storage").textValue());
                assertEquals(output.getBlobContainerName(), entry.get("container").textValue());
                assertEquals(filename, entry.get("filename").textValue());
                assertEquals(root + filename, entry.get("blobName").textValue());
                assertEquals("image/png", entry.get("contentType").textValue());
                byte[] expected = i == 0 ? firstPage : secondPage;
                assertEquals(expected.length, entry.get("sizeBytes").longValue());
                assertEquals("image/png", output.getBlobClient(root + filename).getProperties().getContentType());
                assertArrayEquals(expected, output.getBlobClient(root + filename).downloadContent().toBytes());
            }
            assertThrows(IllegalStateException.class, batch::finish);
            assertThrows(IllegalStateException.class,
                    () -> batch.writePage(3, new ConversionResult(firstPage, "image/png", "page.png", 1)));

            // A retried job creates a different attempt directory, preserving the previous manifest.
            JobStore.ImageOutput retry = store.beginImages(request);
            retry.writePage(1, new ConversionResult(secondPage, "image/png", "page.png", 1));
            JobRecord.ResultLocation retried = retry.finish();
            assertNotEquals(location.blobName(), retried.blobName());
            assertEquals(2, new ObjectMapper().readTree(store.readResult(location).bytes()).get("pageCount").intValue());

            JobStore.ImageOutput jpeg = store.beginImages(imageRequest(id, "archive", output.getBlobContainerName(), "jpeg"));
            jpeg.writePage(7, new ConversionResult(firstPage, "image/jpeg", "page.jpeg", 1));
            JsonNode jpegEntry = new ObjectMapper().readTree(store.readResult(jpeg.finish()).bytes()).get("images").get(0);
            assertEquals(7, jpegEntry.get("page").intValue());
            assertEquals("page-0007.jpeg", jpegEntry.get("filename").textValue());
            assertEquals("image/jpeg", output.getBlobClient(jpegEntry.get("blobName").textValue()).getProperties().getContentType());
        } finally {
            output.deleteIfExists();
        }
    }

    @Test
    void enforcesImageAndManifestAggregateLimitBeforePublishingSuccess() {
        String connection = System.getenv("CONVERSION_TEST_STORAGE_CONNECTION_STRING");
        String id = UUID.randomUUID().toString();
        BlobContainerClient output = container(connection, "image-limits-" + id);
        ConversionJobRequest request = imageRequest(id, "default", output.getBlobContainerName(), "png");
        try {
            AzureJobStore tiny = new AzureJobStore(connection, new ConversionLimits(100, 50, 1_000_000, 5));
            JobStore.ImageOutput oversizedPage = tiny.beginImages(request);
            assertEquals("OUTPUT_LIMIT_EXCEEDED", assertThrows(ConversionException.class,
                    () -> oversizedPage.writePage(1, new ConversionResult(new byte[6], "image/png", "page.png", 1))).code());
            assertEquals(0, output.listBlobs().stream().count());
            assertThrows(IllegalStateException.class, oversizedPage::finish);

            JobStore.ImageOutput aggregate = tiny.beginImages(request);
            aggregate.writePage(1, new ConversionResult(new byte[3], "image/png", "page.png", 1));
            assertEquals("OUTPUT_LIMIT_EXCEEDED", assertThrows(ConversionException.class,
                    () -> aggregate.writePage(2, new ConversionResult(new byte[3], "image/png", "page.png", 1))).code());
            assertEquals(1, output.listBlobs().stream().count());
            assertThrows(IllegalStateException.class, aggregate::finish);

            // The image fits, but a completed result must include the bytes of its manifest too.
            JobStore.ImageOutput manifestTooLarge = tiny.beginImages(request);
            manifestTooLarge.writePage(1, new ConversionResult(new byte[5], "image/png", "page.png", 1));
            assertEquals("OUTPUT_LIMIT_EXCEEDED", assertThrows(ConversionException.class, manifestTooLarge::finish).code());
            assertTrue(output.listBlobs().stream().noneMatch(blob -> blob.getName().endsWith("manifest.json")));

            AzureJobStore generous = new AzureJobStore(connection);
            JobStore.ImageOutput sample = generous.beginImages(request);
            sample.writePage(1, new ConversionResult(new byte[5], "image/png", "page.png", 1));
            long exactBytes = 5L + generous.readResult(sample.finish()).bytes().length;
            AzureJobStore exact = new AzureJobStore(connection, new ConversionLimits(100, 50, 1_000_000, exactBytes));
            JobStore.ImageOutput exactBatch = exact.beginImages(request);
            exactBatch.writePage(1, new ConversionResult(new byte[5], "image/png", "page.png", 1));
            assertEquals(1, exactBatch.finish().pageCount());

            AzureJobStore oneByteShort = new AzureJobStore(connection, new ConversionLimits(100, 50, 1_000_000, exactBytes - 1));
            JobStore.ImageOutput limited = oneByteShort.beginImages(request);
            limited.writePage(1, new ConversionResult(new byte[5], "image/png", "page.png", 1));
            assertEquals("OUTPUT_LIMIT_EXCEEDED", assertThrows(ConversionException.class, limited::finish).code());
        } finally {
            output.deleteIfExists();
        }
    }

    private static ConversionJobRequest imageRequest(String id, String storage, String container, String format) {
        return new ConversionJobRequest(1, id,
                new ConversionJobRequest.BlobSource("external-input", "slides.pptx"),
                new ConversionJobRequest.BlobOutput(storage, container, "processed", "images"),
                "slides.pptx", new ConversionOptions(null, format, null)).normalized();
    }

    private static BlobContainerClient container(String connection, String name) {
        return new BlobContainerClientBuilder().connectionString(connection).containerName(name).buildClient();
    }

    private static void deletePrefix(BlobContainerClient container, String prefix) {
        if (container.exists()) {
            for (var blob : container.listBlobs(new ListBlobsOptions().setPrefix(prefix), Duration.ofSeconds(30))) {
                container.getBlobClient(blob.getName()).deleteIfExists();
            }
        }
    }
}
