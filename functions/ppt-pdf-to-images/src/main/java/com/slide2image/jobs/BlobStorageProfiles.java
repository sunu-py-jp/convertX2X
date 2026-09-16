package com.slide2image.jobs;

import com.slide2image.conversion.ConversionException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Role-specific registered accounts; queue requests cannot introduce credentials or URLs. */
public final class BlobStorageProfiles {
    private final Map<String, StorageConnection> inputs;
    private final Map<String, StorageConnection> outputs;
    private BlobStorageProfiles(Map<String, StorageConnection> inputs, Map<String, StorageConnection> outputs) {
        this.inputs = Map.copyOf(inputs); this.outputs = Map.copyOf(outputs);
    }
    public static BlobStorageProfiles from(Map<String, String> settings, String defaultConnection) {
        StorageConnection control = StorageConnection.from(settings, "CONVERSION_STORAGE", "CONVERSION_STORAGE_CONNECTION_STRING");
        // Preserve the existing explicitly supplied default, including a deliberately absent value.
        if (defaultConnection != null && !defaultConnection.isBlank())
            control = new StorageConnection(defaultConnection.trim(), "", "", "");
        else control = new StorageConnection("", control.blobServiceUri(), control.queueServiceUri(), control.clientId());
        Map<String, StorageConnection> inputs = new HashMap<>(), outputs = new HashMap<>();
        if (control.hasBlob()) { inputs.put("default", control); outputs.put("default", control); }
        read(settings, "CONVERSION_INPUT_STORAGE_", inputs);
        read(settings, "CONVERSION_OUTPUT_STORAGE_", outputs);
        return new BlobStorageProfiles(inputs, outputs);
    }
    private static void read(Map<String, String> settings, String prefix, Map<String, StorageConnection> target) {
        Set<String> aliases = new HashSet<>();
        settings.forEach((key, value) -> {
            if (!key.startsWith(prefix) || value == null || value.isBlank()) return;
            String suffix = key.substring(prefix.length());
            String alias = suffix;
            for (String property : new String[]{"__blobServiceUri", "__clientId"})
                if (suffix.endsWith(property)) alias = suffix.substring(0, suffix.length() - property.length());
            if (!alias.matches("[A-Z][A-Z0-9_]{0,31}") || alias.equals("DEFAULT")
                    || !(suffix.equals(alias) || suffix.equals(alias + "__blobServiceUri") || suffix.equals(alias + "__clientId")))
                throw new IllegalArgumentException(prefix + " requires a valid uppercase alias and supported connection setting");
            aliases.add(alias);
        });
        for (String alias : aliases) {
            StorageConnection connection = StorageConnection.from(settings, prefix + alias, prefix + alias);
            if (!connection.hasBlob()) throw new IllegalArgumentException("A registered Blob alias requires a connection string or blobServiceUri.");
            target.put(alias.toLowerCase(Locale.ROOT), connection);
        }
    }
    public StorageConnection input(String alias) { return resolve(inputs, alias, "UNKNOWN_INPUT_STORAGE"); }
    public StorageConnection output(String alias) { return resolve(outputs, alias, "UNKNOWN_OUTPUT_STORAGE"); }
    public String inputConnection(String alias) { return input(alias).connectionString(); }
    public String outputConnection(String alias) { return output(alias).connectionString(); }
    private static StorageConnection resolve(Map<String, StorageConnection> map, String alias, String code) {
        StorageConnection connection = map.get(alias);
        if (connection == null) throw new ConversionException(400, code, "The storage alias is not registered for this operation.");
        return connection;
    }
    @Override public String toString() { return "BlobStorageProfiles[inputAliases=" + inputs.keySet() + ", outputAliases=" + outputs.keySet() + "]"; }
}
