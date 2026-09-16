package com.convertx2x.office2md.jobs;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueClientBuilder;
import com.azure.storage.queue.QueueMessageEncoding;
import java.net.URI;
import java.util.Map;

/** Server configuration only. Never constructed from queue-message URLs or credentials. */
public record StorageConnection(String connectionString, String blobServiceUri, String queueServiceUri, String clientId) {
    public StorageConnection {
        connectionString = clean(connectionString); blobServiceUri = clean(blobServiceUri);
        queueServiceUri = clean(queueServiceUri); clientId = clean(clientId);
        if (connectionString != null && (blobServiceUri != null || queueServiceUri != null || clientId != null))
            throw new IllegalArgumentException("Choose either a connection string or managed identity service settings, not both.");
        if (connectionString == null) { endpoint(blobServiceUri); endpoint(queueServiceUri); }
        if (clientId != null && !clientId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("Managed identity clientId must be a UUID.");
    }
    public static StorageConnection from(Map<String, String> values) {
        return new StorageConnection(values.get("CONVERSION_STORAGE_CONNECTION_STRING"), values.get("CONVERSION_STORAGE__blobServiceUri"),
                values.get("CONVERSION_STORAGE__queueServiceUri"), values.get("CONVERSION_STORAGE__clientId"));
    }
    public boolean configured() { return connectionString != null || (blobServiceUri != null && queueServiceUri != null); }
    public BlobServiceClient blobs() {
        try {
            var builder = new BlobServiceClientBuilder();
            if (connectionString != null) builder.connectionString(connectionString);
            else { if (blobServiceUri == null) throw new IllegalArgumentException(); builder.endpoint(blobServiceUri).credential(credential()); }
            return builder.buildClient();
        } catch (RuntimeException failure) { throw new IllegalStateException("A configured blob connection is invalid."); }
    }
    public QueueClient queue(String name) {
        try {
            var builder = new QueueClientBuilder().queueName(name).messageEncoding(QueueMessageEncoding.BASE64);
            if (connectionString != null) builder.connectionString(connectionString);
            else { if (queueServiceUri == null) throw new IllegalArgumentException(); builder.endpoint(queueServiceUri).credential(credential()); }
            return builder.buildClient();
        } catch (RuntimeException failure) { throw new IllegalStateException("A configured queue connection is invalid."); }
    }
    private TokenCredential credential() {
        var builder = new ManagedIdentityCredentialBuilder();
        if (clientId != null) builder.clientId(clientId);
        return builder.build();
    }
    private static void endpoint(String value) {
        if (value == null) return;
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException();
        } catch (RuntimeException failure) { throw new IllegalArgumentException("Managed identity service URI must be an HTTPS account endpoint without credentials or a path."); }
    }
    static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    @Override public String toString() { return "StorageConnection[configured=" + configured() + "]"; }
}
