package com.slide2image.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueClientBuilder;
import com.azure.storage.queue.QueueMessageEncoding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slide2image.conversion.ConversionException;
import com.slide2image.conversion.ConversionLimits;
import com.slide2image.conversion.ConversionOptions;
import com.slide2image.conversion.ConversionResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "CONVERSION_TEST_STORAGE_CONNECTION_STRING", matches = ".+")
class IntegrationStorageTest {
    private final String connection = System.getenv("CONVERSION_TEST_STORAGE_CONNECTION_STRING");
    private final ObjectMapper json = new ObjectMapper();
    private final Instant started = Instant.parse("2026-09-16T00:00:00Z");
    private Map<String,String> settings() { return new HashMap<>(Map.of("CONVERSION_STORAGE_CONNECTION_STRING", connection)); }
    private AzureJobStore store(Map<String,String> settings) {
        return new AzureJobStore(IntegrationSettings.from(settings), ConversionLimits.defaults(), BlobStorageProfiles.from(settings, connection));
    }
    private BlobContainerClient container(String name) {
        return new BlobContainerClientBuilder().connectionString(connection).containerName(name).buildClient();
    }
    private QueueClient queue(String name) {
        return new QueueClientBuilder().connectionString(connection).queueName(name).messageEncoding(QueueMessageEncoding.BASE64).buildClient();
    }
    private ConversionJobRequest request(String id, String bucket, String eTag, String notification) {
        return new ConversionJobRequest(2, id, new ConversionJobRequest.BlobSource("default", bucket, "source.pdf", eTag),
                new ConversionJobRequest.BlobOutput("default", bucket, "output", "images", "page-number"),
                "source.pdf", new ConversionOptions(null, "png", null), Map.of("revision", "r42"),
                notification == null ? null : new ConversionJobRequest.Notification(notification)).normalized();
    }
    private JobRecord queued(ConversionJobRequest request) {
        return new JobRecord(new JobStatus(request.jobId(), "queued", request.filename(), request.options(), started.toString(),
                started.toString(), null, null, null), null, request);
    }
    private AzureJobService service(AzureJobStore store, AtomicInteger conversions) {
        return new AzureJobService(store, (bytes, name, options) -> { conversions.incrementAndGet(); return new ConversionResult(new byte[]{9}, "application/zip", "result.zip", 1); },
                (bytes, name, options, consumer) -> { conversions.incrementAndGet(); consumer.accept(1, new ConversionResult(new byte[]{1,2,3}, "image/png", "unused.png", 1)); return 1; },
                (bytes, name, options) -> {}, Clock.fixed(started, ZoneOffset.UTC));
    }
    private void sweep(AzureJobStore store, Instant when) { for (int i=0; i<30; i++) store.maintain(when); }
    private void cleanup(String id) {
        BlobContainerClient control = container(AzureJobService.CONTAINER_NAME);
        if (!control.exists()) return;
        control.getBlobClient("_maintenance/jobs/"+id+".json").deleteIfExists();
        for (String prefix : new String[]{id+"/", "_owned/"+id+"/"})
            for (var blob : control.listBlobs(new ListBlobsOptions().setPrefix(prefix), Duration.ofSeconds(10))) control.getBlobClient(blob.getName()).deleteIfExists();
    }

    @Test void validatesRequestedVersionAndPublishesHashMetadataNamingAndResultNotification() throws Exception {
        String id=UUID.randomUUID().toString(), stale=UUID.randomUUID().toString(), bucket="integration-"+id, q="result-"+id;
        BlobContainerClient files=container(bucket); QueueClient events=queue(q); files.create();
        Map<String,String> settings=settings(); settings.put("CONVERSION_RESULT_QUEUE_EVENTS__queueName",q); settings.put("CONVERSION_RESULT_QUEUE_EVENTS__connectionString",connection);
        AzureJobStore store=store(settings); AtomicInteger calls=new AtomicInteger(); AzureJobService service=service(store,calls);
        try {
            var source=files.getBlobClient("source.pdf"); source.upload(BinaryData.fromBytes(new byte[]{7,8})); String firstETag=source.getProperties().getETag();
            ConversionJobRequest request=request(id,bucket,firstETag,"events"); service.process(request.toJson());
            JobRecord complete=store.find(id).orElseThrow(); assertEquals("succeeded",complete.job().status()); assertEquals(firstETag,complete.input().eTag());
            byte[] manifestBytes=store.readResult(complete.result()).bytes(); var manifest=json.readTree(manifestBytes);
            assertEquals(2,manifest.path("version").intValue()); assertEquals(firstETag,manifest.path("input").path("eTag").textValue());
            assertEquals("r42",manifest.path("metadata").path("revision").textValue()); assertEquals("1.png",manifest.path("images").get(0).path("filename").textValue());
            assertEquals(AzureJobStore.sha256(new byte[]{1,2,3}),manifest.path("images").get(0).path("sha256").textValue());
            assertEquals(AzureJobStore.sha256(manifestBytes),complete.result().sha256());
            var messages=events.receiveMessages(10); var event=json.readTree(messages.iterator().next().getMessageText());
            assertEquals(id+":succeeded",event.path("eventId").textValue()); assertEquals(firstETag,event.path("input").path("eTag").textValue());
            assertEquals(complete.result().sha256(),event.path("result").path("sha256").textValue());
            service.process(request.toJson()); assertEquals(1,calls.get()); assertFalse(store.find(id).orElseThrow().notificationPending());
            source.upload(BinaryData.fromBytes(new byte[]{9}),true);
            ConversionJobRequest old=request(stale,bucket,firstETag,"events"); service.process(old.toJson());
            assertEquals("INPUT_VERSION_MISMATCH",store.find(stale).orElseThrow().job().errorCode()); assertEquals(1,calls.get());
        } finally { events.deleteIfExists(); files.deleteIfExists(); cleanup(id); cleanup(stale); }
    }

    @Test void maintenanceRetriesOnlyNotificationsProtectsPendingArtifactsThenExpiresOwnedFilesAndState() throws Exception {
        String id=UUID.randomUUID().toString(), bucket="outbox-"+id, q="events-"+id;
        BlobContainerClient files=container(bucket); files.create(); container(AzureJobService.CONTAINER_NAME).createIfNotExists();
        QueueClient events=queue(q); Map<String,String> settings=settings();
        settings.putAll(Map.of("CONVERSION_CREATE_RESOURCES","false","CONVERSION_RESULT_QUEUE_EVENTS__queueName",q,
                "CONVERSION_RESULT_QUEUE_EVENTS__connectionString",connection,"CONVERSION_RESULT_RETENTION_DAYS","1","CONVERSION_STATE_RETENTION_DAYS","3"));
        AzureJobStore store=store(settings); AtomicInteger calls=new AtomicInteger(); AzureJobService service=service(store,calls);
        try {
            files.getBlobClient("source.pdf").upload(BinaryData.fromBytes(new byte[]{4}));
            ConversionJobRequest request=request(id,bucket,null,"events"); service.process(request.toJson());
            JobRecord complete=store.find(id).orElseThrow(); assertTrue(complete.notificationPending());
            sweep(store,started.plus(Duration.ofDays(2))); assertTrue(files.getBlobClient(complete.result().blobName()).exists());
            assertFalse(store.find(id).orElseThrow().resultExpired()); assertEquals(404, assertThrows(com.azure.storage.queue.models.QueueStorageException.class, events::getProperties).getStatusCode());
            // Provisioning is external in createResources=false mode. Sending now succeeds without rendering again.
            events.create(); sweep(store,started.plus(Duration.ofDays(2)));
            assertEquals(1,calls.get()); assertEquals(1,events.peekMessages(10, Duration.ofSeconds(10), com.azure.core.util.Context.NONE).stream().count());
            assertFalse(store.find(id).orElseThrow().notificationPending()); assertFalse(store.find(id).orElseThrow().resultExpired());
            // Late notification starts a fresh result retention window for its consumer.
            assertTrue(files.getBlobClient(complete.result().blobName()).exists());
            sweep(store,started.plus(Duration.ofDays(4)));
            assertTrue(store.find(id).orElseThrow().resultExpired());
            assertEquals("JOB_RESULT_EXPIRED",assertThrows(ConversionException.class,()->service.download(id)).code());
            assertFalse(files.getBlobClient(complete.result().blobName()).exists()); assertTrue(files.getBlobClient("source.pdf").exists());
            sweep(store,started.plus(Duration.ofDays(6))); assertTrue(store.find(id).isEmpty());
        } finally { events.deleteIfExists(); files.deleteIfExists(); cleanup(id); }
    }

    @Test void cleanupPreservesCallerReplacementsAndUnregisteredAdjacentObjects() throws Exception {
        String id=UUID.randomUUID().toString(), bucket="ownership-"+id; BlobContainerClient files=container(bucket);files.create();
        Map<String,String> settings=settings(); settings.put("CONVERSION_RESULT_RETENTION_DAYS","1"); AzureJobStore store=store(settings);
        try {
            files.getBlobClient("source.pdf").upload(BinaryData.fromBytes(new byte[]{5}));
            service(store,new AtomicInteger()).process(request(id,bucket,null,null).toJson());
            JobRecord completed=store.find(id).orElseThrow(); var manifest=json.readTree(store.readResult(completed.result()).bytes());
            String page=manifest.path("images").get(0).path("blobName").textValue();
            // An overwrite may preserve the ownership metadata, but a different ETag still protects it.
            var oldMetadata=files.getBlobClient(page).getProperties().getMetadata();
            files.getBlobClient(page).upload(BinaryData.fromString("caller replacement"),true); files.getBlobClient(page).setMetadata(oldMetadata);
            String adjacent=page+".caller"; files.getBlobClient(adjacent).upload(BinaryData.fromString("caller-owned"));
            sweep(store,started.plus(Duration.ofDays(2)));
            assertEquals("caller replacement",files.getBlobClient(page).downloadContent().toString()); assertTrue(files.getBlobClient(adjacent).exists());
            assertTrue(files.getBlobClient("source.pdf").exists()); assertFalse(files.getBlobClient(completed.result().blobName()).exists());
        } finally { files.deleteIfExists();cleanup(id); }
    }

    @Test void replayingLegacyTerminalStateDoesNotAdoptItForRetention() throws Exception {
        String id=UUID.randomUUID().toString(), bucket="legacy-"+id; BlobContainerClient files=container(bucket); files.create();
        Map<String,String> settings=settings();settings.putAll(Map.of("CONVERSION_RESULT_RETENTION_DAYS","1","CONVERSION_STATE_RETENTION_DAYS","3"));
        AzureJobStore store=store(settings); AtomicInteger conversions=new AtomicInteger(); AzureJobService service=service(store,conversions);
        try {
            files.getBlobClient("source.pdf").upload(BinaryData.fromBytes(new byte[]{1}));
            ConversionJobRequest request=request(id,bucket,null,null); service.process(request.toJson());
            JobRecord complete=store.find(id).orElseThrow();
            var old=json.valueToTree(complete); ((com.fasterxml.jackson.databind.node.ObjectNode)old).remove("artifactTrackingVersion");
            BlobContainerClient control=container(AzureJobService.CONTAINER_NAME);
            control.getBlobClient(id+"/status.json").upload(BinaryData.fromBytes(json.writeValueAsBytes(old)),true);
            for(var item:control.listBlobs(new ListBlobsOptions().setPrefix("_owned/"+id+"/"),Duration.ofSeconds(10)))
                control.getBlobClient(item.getName()).deleteIfExists();
            service.process(request.toJson()); // A replay recreates discovery but must not claim ownership.
            sweep(store,started.plus(Duration.ofDays(6)));
            assertEquals(1,conversions.get()); assertTrue(store.find(id).isPresent());
            assertFalse(store.find(id).orElseThrow().resultExpired()); assertTrue(files.getBlobClient(complete.result().blobName()).exists());
        } finally { files.deleteIfExists(); cleanup(id); }
    }

    @Test void httpEnqueueFailureLeavesTerminalOwnedStateThatCanBeCleaned() {
        Map<String,String> settings=settings(); settings.putAll(Map.of("CONVERSION_CREATE_RESOURCES","false","CONVERSION_RESULT_RETENTION_DAYS","1"));
        AzureJobStore store=store(settings); BlobContainerClient control=container(AzureJobService.CONTAINER_NAME); control.createIfNotExists();
        QueueClient jobs=queue(AzureJobService.QUEUE_NAME); jobs.deleteIfExists();
        String filename="submission-failure-"+UUID.randomUUID()+".pdf"; String[] id={null};
        try {
            assertThrows(com.azure.storage.queue.models.QueueStorageException.class,
                    ()->service(store,new AtomicInteger()).submit(new byte[]{1,2},filename,new ConversionOptions(null,"png",null)));
            for(var item:control.listBlobs()) {
                if(!item.getName().matches("[0-9a-f-]{36}/status\\.json")) continue;
                JobRecord record=store.find(item.getName().substring(0,36)).orElseThrow();
                if(record.job().filename().equals(filename)) { id[0]=record.job().id(); break; }
            }
            assertNotNull(id[0]);
            assertEquals("SUBMISSION_FAILED",store.find(id[0]).orElseThrow().job().errorCode());
            assertTrue(control.getBlobClient(id[0]+"/input").exists());
            sweep(store,started.plus(Duration.ofDays(2)));
            assertFalse(control.getBlobClient(id[0]+"/input").exists());
        } finally { jobs.createIfNotExists(); if(id[0]!=null)cleanup(id[0]); }
    }

    @Test void staleInterruptedHttpSubmissionExpiresButNormalQueuedAndRunningJobsAreProtected() {
        Map<String,String> settings=settings();settings.put("CONVERSION_RESULT_RETENTION_DAYS","1");AzureJobStore store=store(settings);
        String abandoned=UUID.randomUUID().toString(), queued=UUID.randomUUID().toString(), running=UUID.randomUUID().toString();
        try {
            for(String id:new String[]{abandoned,queued,running}) {
                ConversionJobRequest request=new ConversionJobRequest(1,id,new ConversionJobRequest.BlobSource(AzureJobService.CONTAINER_NAME,id+"/input"),new ConversionJobRequest.BlobOutput(AzureJobService.CONTAINER_NAME,""),"document.pdf",null).normalized();
                JobRecord base=queued(request);
                JobStatus status=id.equals(running)?new JobStatus(id,"running",base.job().filename(),base.job().options(),started.toString(),started.toString(),null,null,null):base.job();
                store.create(new JobRecord(status,null,request,null,null,false,id.equals(abandoned)),new byte[]{1});
            }
            sweep(store,started.plus(Duration.ofDays(2)));
            assertEquals("SUBMISSION_EXPIRED",store.find(abandoned).orElseThrow().job().errorCode());
            assertEquals(started.plus(Duration.ofDays(2)).toString(),store.find(abandoned).orElseThrow().job().updatedAt());
            sweep(store,started.plus(Duration.ofDays(3)));
            assertFalse(container(AzureJobService.CONTAINER_NAME).getBlobClient(abandoned+"/input").exists());
            assertTrue(container(AzureJobService.CONTAINER_NAME).getBlobClient(queued+"/input").exists());
            assertTrue(container(AzureJobService.CONTAINER_NAME).getBlobClient(running+"/input").exists());
        } finally { cleanup(abandoned);cleanup(queued);cleanup(running); }
    }
}
