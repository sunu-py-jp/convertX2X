package com.convertx2x.md2pdf;

import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.queue.QueueClientBuilder;
import com.azure.storage.queue.QueueMessageEncoding;
import com.convertx2x.md2pdf.jobs.AzureJobService;

/** Creates local Azurite resources before Queue listeners start. */
public final class LocalStoragePreparation {
    private LocalStoragePreparation() { }

    public static void main(String[] args) {
        String connection = System.getenv(AppConfig.STORAGE_SETTING);
        if (connection == null || !connection.trim().equalsIgnoreCase("UseDevelopmentStorage=true")) return;
        if ("false".equalsIgnoreCase(System.getenv().getOrDefault("CONVERSION_CREATE_RESOURCES", "true").trim())) return;
        new BlobServiceClientBuilder().connectionString(connection).buildClient()
                .getBlobContainerClient(AzureJobService.CONTAINER_NAME).createIfNotExists();
        for (String name : new String[] {AzureJobService.QUEUE_NAME, AzureJobService.QUEUE_NAME + "-poison"}) {
            new QueueClientBuilder().connectionString(connection).queueName(name)
                    .messageEncoding(QueueMessageEncoding.BASE64).buildClient().createIfNotExists();
        }
    }
}
