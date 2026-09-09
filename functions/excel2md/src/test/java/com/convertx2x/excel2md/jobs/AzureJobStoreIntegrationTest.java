package com.convertx2x.excel2md.jobs;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.convertx2x.excel2md.conversion.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Opt in with an isolated test Storage account/Azurite; no network is needed by the ordinary tests. */
@EnabledIfEnvironmentVariable(named = "CONVERSION_TEST_STORAGE_CONNECTION_STRING", matches = ".+")
class AzureJobStoreIntegrationTest {
    @Test void storesCompleteAttemptsWithEtagsMimeTypesPrivateContainersAndLeaseFencing() throws Exception {
        String connection = System.getenv("CONVERSION_TEST_STORAGE_CONNECTION_STRING");
        String id = UUID.randomUUID().toString();
        BlobContainerClient input = container(connection, "input-" + id);
        BlobContainerClient output = container(connection, "output-" + id);
        BlobContainerClient control = container(connection, AzureJobService.CONTAINER_NAME);
        BlobStorageProfiles profiles = BlobStorageProfiles.from(Map.of(
                "CONVERSION_INPUT_STORAGE_SOURCE", connection, "CONVERSION_OUTPUT_STORAGE_DESTINATION", connection), connection);
        AzureJobStore store = new AzureJobStore(connection, ConversionLimits.defaults(), profiles);
        ConversionJobRequest request = new ConversionJobRequest(1, id,
                new ConversionJobRequest.BlobSource("source", input.getBlobContainerName(), "incoming/日本語.xlsx"),
                new ConversionJobRequest.BlobOutput("destination", output.getBlobContainerName(), "customer"), "日本語.xlsx");
        JobStatus queued = new JobStatus(id, "queued", request.filename(), "now", "now", null, null, null, null);
        JobRecord original = new JobRecord(queued, null, request);
        try {
            input.create();
            input.getBlobClient(request.input().blobName()).upload(BinaryData.fromBytes(new byte[]{1,2,3}));
            store.ensure(original);
            store.validateLocations(request);
            assertArrayEquals(new byte[]{1,2,3}, store.readInput(request.input()));
            assertFalse(control.getBlobClient(id + "/input").exists(), "Direct queue uses the producer's blob");
            try (JobStore.JobLock lock = store.lock(id); ConversionResult converted = AzureJobServiceTest.result()) {
                assertThrows(BlobStorageException.class, () -> store.lock(id));
                assertThrows(BlobStorageException.class, () -> control.getBlobClient(id + "/status.json").upload(BinaryData.fromString("{}"), true));
                JobRecord.ResultLocation first = store.writeResult(request, converted);
                JobRecord.ResultLocation second = store.writeResult(request, converted);
                assertEquals(3, first.artifacts().size());
                String root = first.artifacts().getFirst().blobName().replace("document.md", "");
                assertTrue(root.startsWith("customer/" + id + "/results/"));
                assertNotEquals(root, second.artifacts().getFirst().blobName().replace("document.md", ""));
                for (var artifact : first.artifacts()) {
                    assertTrue(artifact.blobName().startsWith(root));
                    var properties = output.getBlobClient(artifact.blobName()).getProperties();
                    assertEquals(artifact.contentType(), properties.getContentType());
                    assertEquals(artifact.eTag(), properties.getETag());
                    assertEquals(artifact.sizeBytes(), properties.getBlobSize());
                }
                assertNull(output.getAccessPolicy().getBlobAccessType());
                var done = new JobStatus(id, "succeeded", request.filename(), "now", "later", 1, 0, null, null);
                lock.update(new JobRecord(done, first, request));
                store.ensure(original);
                assertEquals(done, store.find(id).orElseThrow().job());
                assertEquals(first, store.find(id).orElseThrow().result());
                assertEquals("image/png", store.readResult(first, "images/image-0001.png").contentType());
                Map<String, byte[]> files = new HashMap<>();
                try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(store.readArchive(first).bytes()))) {
                    for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) files.put(entry.getName(), zip.readAllBytes());
                }
                assertArrayEquals(store.readResult(first, "document.md").bytes(), files.get("document.md"));
                assertEquals(Set.of("document.md", "report.json", "images/image-0001.png"), files.keySet());
                assertEquals("ARTIFACT_NOT_FOUND", assertThrows(ConversionException.class,
                        () -> store.readResult(first, "images/not-listed.png")).code());
                assertThrows(ConversionException.class, () -> store.readResult(first, "../status.json"));
                // A same-length replacement must still be rejected after the completed attempt was published.
                var markdown = first.artifacts().stream().filter(file -> file.path().equals("document.md")).findFirst().orElseThrow();
                output.getBlobClient(markdown.blobName()).upload(BinaryData.fromBytes(new byte[(int)markdown.sizeBytes()]), true);
                assertEquals("RESULT_CHANGED", assertThrows(ConversionException.class,
                        () -> store.readResult(first, "document.md")).code());
            }
        } finally {
            input.deleteIfExists(); output.deleteIfExists(); deletePrefix(control, id + "/");
        }
    }

    @Test void rejectsOversizedInputsBeforeDownloadAndEnforcesOutputBudget() {
        String connection = System.getenv("CONVERSION_TEST_STORAGE_CONNECTION_STRING");
        String id = UUID.randomUUID().toString();
        BlobContainerClient input = container(connection, "limits-" + id);
        BlobContainerClient output = container(connection, "outlimit-" + id);
        ConversionLimits d = ConversionLimits.defaults();
        ConversionLimits small = new ConversionLimits(2, d.maxSheets(), d.maxReadCells(), d.maxTableCells(),
                d.maxMarkdownBytes(), d.maxImages(), d.maxImageBytes(), 5, d.maxShapes(), d.maxGroupDepth(), d.maxImagePixels());
        AzureJobStore store = new AzureJobStore(connection, small);
        try {
            input.create(); input.getBlobClient("large.xlsx").upload(BinaryData.fromBytes(new byte[]{1,2,3}));
            assertEquals("INPUT_LIMIT_EXCEEDED", assertThrows(ConversionException.class,
                    () -> store.readInput(new ConversionJobRequest.BlobSource(input.getBlobContainerName(), "large.xlsx"))).code());
            assertEquals("INPUT_NOT_FOUND", assertThrows(ConversionException.class,
                    () -> store.readInput(new ConversionJobRequest.BlobSource(input.getBlobContainerName(), "missing.xlsx"))).code());
            var request = new ConversionJobRequest(1, id, new ConversionJobRequest.BlobSource(input.getBlobContainerName(), "large.xlsx"),
                    new ConversionJobRequest.BlobOutput(output.getBlobContainerName(), ""), "large.xlsx");
            try (ConversionResult result = AzureJobServiceTest.result()) {
                assertEquals("OUTPUT_LIMIT_EXCEEDED", assertThrows(ConversionException.class,
                        () -> store.writeResult(request, result)).code());
            }
        } finally { input.deleteIfExists(); output.deleteIfExists(); }
    }

    private static BlobContainerClient container(String connection, String name) {
        return new BlobContainerClientBuilder().connectionString(connection).containerName(name).buildClient();
    }
    private static void deletePrefix(BlobContainerClient container, String prefix) {
        if (container.exists()) for (var blob : container.listBlobs(new ListBlobsOptions().setPrefix(prefix), Duration.ofSeconds(30))) {
            container.getBlobClient(blob.getName()).deleteIfExists();
        }
    }
}
