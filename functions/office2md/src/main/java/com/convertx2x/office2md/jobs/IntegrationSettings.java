package com.convertx2x.office2md.jobs;

import com.convertx2x.office2md.conversion.ConversionException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Startup-validated settings for storage, registered result queues and owned-artifact retention. */
public record IntegrationSettings(StorageConnection storage, boolean createResources, int resultRetentionDays,
                                  int stateRetentionDays, Map<String, ResultQueue> resultQueues) {
    public record ResultQueue(String queueName, StorageConnection storage) { }
    public IntegrationSettings { resultQueues = Map.copyOf(resultQueues); }
    public static IntegrationSettings from(Map<String, String> values) {
        var storage = StorageConnection.from(values);
        if (storage.connectionString() == null && (((storage.blobServiceUri() == null) != (storage.queueServiceUri() == null)) || (storage.clientId() != null && storage.blobServiceUri() == null)))
            throw new IllegalArgumentException("Managed identity requires both blobServiceUri and queueServiceUri.");
        String create = StorageConnection.clean(values.get("CONVERSION_CREATE_RESOURCES"));
        if (create == null) create = "true";
        if (!create.equals("true") && !create.equals("false")) throw new IllegalArgumentException("CONVERSION_CREATE_RESOURCES must be true or false.");
        int result = days(values, "CONVERSION_RESULT_RETENTION_DAYS"), state = days(values, "CONVERSION_STATE_RETENTION_DAYS");
        if (state > 0 && (result == 0 || state <= result)) throw new IllegalArgumentException("State retention requires enabled result retention and must exceed it.");
        Map<String, ResultQueue> queues = new HashMap<>();
        String prefix = "CONVERSION_RESULT_QUEUE_";
        values.forEach((key, value) -> {
            if (!key.startsWith(prefix) || value == null || value.isBlank()) return;
            String rest = key.substring(prefix.length()); int split = rest.lastIndexOf("__");
            if (split < 1) throw new IllegalArgumentException("Result queue settings require an alias and field.");
            String alias = rest.substring(0, split), field = rest.substring(split + 2);
            if (!alias.matches("[A-Z][A-Z0-9_]{0,31}") || !java.util.Set.of("queueName", "connectionString", "queueServiceUri", "clientId").contains(field))
                throw new IllegalArgumentException("Invalid result queue setting.");
            String p = prefix + alias + "__";
            String name = StorageConnection.clean(values.get(p + "queueName"));
            if (name == null || !name.matches("[a-z0-9](?:[a-z0-9]|-(?!-)){1,61}[a-z0-9]")) throw new IllegalArgumentException("Registered result queues need a valid queueName.");
            if (name.equals(AzureJobService.QUEUE_NAME) || name.equals(AzureJobService.QUEUE_NAME + "-poison"))
                throw new IllegalArgumentException("A result queue must be distinct from conversion work and poison queues.");
            StorageConnection connection = new StorageConnection(values.get(p + "connectionString"), null, values.get(p + "queueServiceUri"), values.get(p + "clientId"));
            if (connection.connectionString() == null && connection.queueServiceUri() == null) throw new IllegalArgumentException("Registered result queues require a connection.");
            queues.put(alias.toLowerCase(Locale.ROOT), new ResultQueue(name, connection));
        });
        return new IntegrationSettings(storage, Boolean.parseBoolean(create), result, state, queues);
    }
    public ResultQueue resultQueue(String alias) {
        ResultQueue result = resultQueues.get(alias);
        if (result == null) throw new ConversionException(400, "UNKNOWN_RESULT_QUEUE", "The result queue alias is not registered.");
        return result;
    }
    private static int days(Map<String, String> values, String name) {
        String value = values.get(name); if (value == null || value.isBlank()) return 0;
        try { int days = Integer.parseInt(value.trim()); if (days >= 0 && days <= 36500) return days; } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(name + " must be an integer from 0 to 36500.");
    }
    @Override public String toString() { return "IntegrationSettings[createResources=" + createResources + ", resultRetentionDays=" + resultRetentionDays + ", stateRetentionDays=" + stateRetentionDays + "]"; }
}
