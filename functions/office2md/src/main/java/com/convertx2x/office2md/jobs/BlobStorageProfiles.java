package com.convertx2x.office2md.jobs;

import com.convertx2x.office2md.conversion.ConversionException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pre-registered, role-specific Storage accounts. Queue messages contain aliases, never credentials. */
public final class BlobStorageProfiles {
    private final Map<String, StorageConnection> inputs;
    private final Map<String, StorageConnection> outputs;
    private BlobStorageProfiles(Map<String, StorageConnection> inputs, Map<String, StorageConnection> outputs) {
        this.inputs = Map.copyOf(inputs); this.outputs = Map.copyOf(outputs);
    }
    public static BlobStorageProfiles from(Map<String, String> settings, String defaultConnection) {
        Map<String, StorageConnection> inputs = new HashMap<>(), outputs = new HashMap<>();
        StorageConnection fallback = new StorageConnection(defaultConnection, settings.get("CONVERSION_STORAGE__blobServiceUri"),
                settings.get("CONVERSION_STORAGE__queueServiceUri"), settings.get("CONVERSION_STORAGE__clientId"));
        if (fallback.connectionString() != null || fallback.blobServiceUri() != null) { inputs.put("default", fallback); outputs.put("default", fallback); }
        read(settings, "CONVERSION_INPUT_STORAGE_", inputs); read(settings, "CONVERSION_OUTPUT_STORAGE_", outputs);
        return new BlobStorageProfiles(inputs, outputs);
    }
    private static void read(Map<String, String> settings, String prefix, Map<String, StorageConnection> destination) {
        settings.forEach((name, value) -> {
            if (!name.startsWith(prefix) || value == null || value.isBlank()) return;
            String suffix = name.substring(prefix.length()); int split = suffix.endsWith("__blobServiceUri") || suffix.endsWith("__clientId") ? suffix.lastIndexOf("__") : -1;
            String alias = split < 0 ? suffix : suffix.substring(0, split);
            if (!alias.matches("[A-Z][A-Z0-9_]{0,31}") || alias.equals("DEFAULT")
                    || (split >= 0 && !Set.of("blobServiceUri", "clientId").contains(suffix.substring(split + 2))))
                throw new IllegalArgumentException(prefix + " requires an uppercase alias other than DEFAULT and a supported field.");
            StorageConnection connection = new StorageConnection(settings.get(prefix + alias), settings.get(prefix + alias + "__blobServiceUri"), null,
                    settings.get(prefix + alias + "__clientId"));
            if (connection.connectionString() == null && connection.blobServiceUri() == null) throw new IllegalArgumentException("Blob aliases require a connection or blobServiceUri.");
            destination.put(alias.toLowerCase(Locale.ROOT), connection);
        });
    }
    public StorageConnection input(String alias) { return resolve(inputs, alias, "UNKNOWN_INPUT_STORAGE"); }
    public StorageConnection output(String alias) { return resolve(outputs, alias, "UNKNOWN_OUTPUT_STORAGE"); }
    public String inputConnection(String alias) { return input(alias).connectionString(); }
    public String outputConnection(String alias) { return output(alias).connectionString(); }
    private static StorageConnection resolve(Map<String, StorageConnection> connections, String alias, String code) {
        StorageConnection connection = connections.get(alias);
        if (connection == null) throw new ConversionException(400, code, "The storage alias is not registered for this operation.");
        return connection;
    }
    @Override public String toString() { return "BlobStorageProfiles[inputAliases=" + inputs.keySet() + ", outputAliases=" + outputs.keySet() + "]"; }
}
