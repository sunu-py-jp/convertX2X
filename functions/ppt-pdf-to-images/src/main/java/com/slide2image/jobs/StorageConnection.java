package com.slide2image.jobs;

import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueClientBuilder;
import com.azure.storage.queue.QueueMessageEncoding;
import java.net.URI;
import java.util.Map;

/** Credentials are configured by the operator, never taken from a job message. */
public record StorageConnection(String connectionString, String blobServiceUri, String queueServiceUri, String clientId) {
    public static StorageConnection from(Map<String, String> settings, String prefix, String connectionKey) {
        String connection = value(settings, connectionKey);
        if (!connection.isEmpty()) {
            if (!value(settings, prefix + "__blobServiceUri").isEmpty() || !value(settings, prefix + "__queueServiceUri").isEmpty()
                    || !value(settings, prefix + "__clientId").isEmpty())
                throw new IllegalArgumentException("Choose one Storage authentication mode; connection strings and identity settings cannot be mixed.");
            return new StorageConnection(connection, "", "", "");
        }
        String blob = endpoint(value(settings, prefix + "__blobServiceUri"));
        String queue = endpoint(value(settings, prefix + "__queueServiceUri"));
        String client = value(settings, prefix + "__clientId");
        if (!client.isEmpty() && !client.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Managed identity clientId must be a UUID.");
        return new StorageConnection("", blob, queue, client);
    }
    public boolean hasBlob() { return !connectionString.isEmpty() || !blobServiceUri.isEmpty(); }
    public boolean hasQueue() { return !connectionString.isEmpty() || !queueServiceUri.isEmpty(); }
    public BlobServiceClient blobs() {
        try {
            BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
            return connectionString.isEmpty() ? builder.endpoint(blobServiceUri).credential(credential()).buildClient()
                    : builder.connectionString(connectionString).buildClient();
        } catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Invalid configured Blob connection."); }
    }
    public QueueClient queue(String name) {
        try {
            QueueClientBuilder builder = new QueueClientBuilder().queueName(name).messageEncoding(QueueMessageEncoding.BASE64);
            return connectionString.isEmpty() ? builder.endpoint(queueServiceUri).credential(credential()).buildClient()
                    : builder.connectionString(connectionString).buildClient();
        } catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Invalid configured Queue connection."); }
    }
    private com.azure.core.credential.TokenCredential credential() {
        ManagedIdentityCredentialBuilder builder = new ManagedIdentityCredentialBuilder();
        if (!clientId.isEmpty()) builder.clientId(clientId);
        return builder.build();
    }
    private static String endpoint(String value) {
        if (value.isEmpty()) return value;
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException();
            return value.replaceFirst(":443(?=/|$)", "").replaceAll("/+$", "");
        } catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Storage service URI must be an HTTPS service endpoint without credentials or path."); }
    }
    static String value(Map<String, String> settings, String key) {
        String value = settings.get(key); return value == null ? "" : value.trim();
    }
    @Override public String toString() { return "StorageConnection[authentication=" + (connectionString.isEmpty() ? "managedIdentity" : "connectionString") + "]"; }
}
