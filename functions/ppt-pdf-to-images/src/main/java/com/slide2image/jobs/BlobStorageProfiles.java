package com.slide2image.jobs;

import com.slide2image.conversion.ConversionException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Pre-registered, role-specific Storage accounts. Queue messages contain aliases, never credentials. */
public final class BlobStorageProfiles {
    private static final String INPUT_PREFIX = "CONVERSION_INPUT_STORAGE_";
    private static final String OUTPUT_PREFIX = "CONVERSION_OUTPUT_STORAGE_";
    private final Map<String, String> inputs;
    private final Map<String, String> outputs;

    private BlobStorageProfiles(Map<String, String> inputs, Map<String, String> outputs) {
        this.inputs = Map.copyOf(inputs);
        this.outputs = Map.copyOf(outputs);
    }

    public static BlobStorageProfiles from(Map<String, String> settings, String defaultConnection) {
        Map<String, String> inputs = new HashMap<>();
        Map<String, String> outputs = new HashMap<>();
        if (defaultConnection != null && !defaultConnection.isBlank()) {
            inputs.put("default", defaultConnection.trim());
            outputs.put("default", defaultConnection.trim());
        }
        read(settings, INPUT_PREFIX, inputs);
        read(settings, OUTPUT_PREFIX, outputs);
        return new BlobStorageProfiles(inputs, outputs);
    }

    private static void read(Map<String, String> settings, String prefix, Map<String, String> destination) {
        settings.forEach((name, connection) -> {
            if (!name.startsWith(prefix) || connection == null || connection.isBlank()) return;
            String suffix = name.substring(prefix.length());
            if (!suffix.matches("[A-Z][A-Z0-9_]{0,31}") || suffix.equals("DEFAULT")) {
                throw new IllegalArgumentException(prefix + " requires an uppercase alias other than DEFAULT");
            }
            destination.put(suffix.toLowerCase(Locale.ROOT), connection.trim());
        });
    }

    public String inputConnection(String alias) {
        return resolve(inputs, alias, "UNKNOWN_INPUT_STORAGE");
    }

    public String outputConnection(String alias) {
        return resolve(outputs, alias, "UNKNOWN_OUTPUT_STORAGE");
    }

    private static String resolve(Map<String, String> connections, String alias, String code) {
        String connection = connections.get(alias);
        if (connection == null) {
            throw new ConversionException(400, code, "The storage alias is not registered for this operation.");
        }
        return connection;
    }

    @Override public String toString() {
        return "BlobStorageProfiles[inputAliases=" + inputs.keySet() + ", outputAliases=" + outputs.keySet() + "]";
    }
}
