package com.slide2image.jobs;

import com.slide2image.conversion.ConversionException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record IntegrationSettings(StorageConnection control, boolean createResources, int resultRetentionDays,
                                  int stateRetentionDays, Map<String, QueueDestination> resultQueues) {
    public record QueueDestination(String name, StorageConnection connection) {}
    public static IntegrationSettings from(Map<String, String> settings) {
        StorageConnection control = StorageConnection.from(settings, "CONVERSION_STORAGE", "CONVERSION_STORAGE_CONNECTION_STRING");
        if (control.hasBlob() != control.hasQueue()) throw new IllegalArgumentException("Both Blob and Queue service URIs are required for asynchronous conversion.");
        String create = StorageConnection.value(settings, "CONVERSION_CREATE_RESOURCES");
        if (!create.isEmpty() && !Set.of("true", "false").contains(create)) throw new IllegalArgumentException("CONVERSION_CREATE_RESOURCES must be true or false.");
        int result = days(settings, "CONVERSION_RESULT_RETENTION_DAYS");
        int state = days(settings, "CONVERSION_STATE_RETENTION_DAYS");
        if (state > 0 && (result == 0 || state <= result)) throw new IllegalArgumentException("State retention requires enabled result retention and must be greater.");
        String prefix = "CONVERSION_RESULT_QUEUE_";
        Set<String> aliases = new HashSet<>();
        settings.forEach((key, value) -> {
            if (!key.startsWith(prefix) || value == null || value.isBlank()) return;
            String suffix = key.substring(prefix.length());
            int separator = suffix.lastIndexOf("__");
            String[] parts = separator < 0 ? new String[]{suffix} : new String[]{suffix.substring(0, separator), suffix.substring(separator + 2)};
            if (parts.length != 2 || !parts[0].matches("[A-Z][A-Z0-9_]{0,31}")
                    || !Set.of("queueName", "connectionString", "queueServiceUri", "clientId").contains(parts[1]))
                throw new IllegalArgumentException("Result queue settings require a valid alias and supported property.");
            aliases.add(parts[0]);
        });
        Map<String, QueueDestination> queues = new HashMap<>();
        for (String alias : aliases) {
            String base = prefix + alias;
            String name = StorageConnection.value(settings, base + "__queueName");
            if (!name.matches("[a-z0-9](?:[a-z0-9]|-(?!-)){1,61}[a-z0-9]")) throw new IllegalArgumentException("A result queue requires a valid queueName.");
            StorageConnection connection = StorageConnection.from(settings, base, base + "__connectionString");
            if (!connection.hasQueue()) throw new IllegalArgumentException("A result queue requires a registered connection.");
            if (control.hasQueue() && (name.equals(AzureJobService.QUEUE_NAME) || name.equals(AzureJobService.QUEUE_NAME + "-poison"))
                    && queueAddress(control, name).equals(queueAddress(connection, name)))
                throw new IllegalArgumentException("A result queue must not target this application work or poison queue.");
            queues.put(alias.toLowerCase(Locale.ROOT), new QueueDestination(name, connection));
        }
        return new IntegrationSettings(control, !create.equals("false"), result, state, Map.copyOf(queues));
    }
    private static String queueAddress(StorageConnection connection, String name) {
        // Compare destinations independently of credentials; do not send requests or expose configured URLs.
        try {
            java.net.URI uri = java.net.URI.create(connection.queue(name).getQueueUrl());
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            String authority = uri.getHost().toLowerCase(Locale.ROOT)
                    + (port < 0 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80) ? "" : ":" + port);
            return scheme + "://" + authority + uri.getRawPath();
        } catch (RuntimeException failure) { throw new IllegalArgumentException("A configured result queue connection is invalid."); }
    }
    public QueueDestination resultQueue(String alias) {
        QueueDestination result = resultQueues.get(alias);
        if (result == null) throw new ConversionException(400, "UNKNOWN_RESULT_QUEUE", "The result queue alias is not registered.");
        return result;
    }
    private static int days(Map<String, String> settings, String name) {
        String value = StorageConnection.value(settings, name);
        if (value.isEmpty()) return 0;
        try { int days = Integer.parseInt(value); if (days >= 0 && days <= 36500) return days; } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException(name + " must be an integer from 0 to 36500.");
    }
}
