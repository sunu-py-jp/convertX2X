package com.convertx2x.office2md.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.convertx2x.office2md.conversion.ConversionException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** A versioned queue request. Storage names refer to server-configured aliases, never credentials or URLs. */
public record ConversionJobRequest(int version, String jobId, BlobSource input, BlobOutput output,
                                   String filename, Map<String, String> metadata, Notification notification) {
    public static final int VERSION = 1;
    public static final int LATEST_VERSION = 2;
    public ConversionJobRequest(int version, String jobId, BlobSource input, BlobOutput output, String filename) {
        this(version, jobId, input, output, filename, Map.of(), null);
    }
    public ConversionJobRequest { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    public record Notification(String queue) { }

    public static final int MAX_MESSAGE_BYTES = 48 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public record BlobSource(String storage, String container, String blobName, String expectedETag) {
        public BlobSource(String storage, String container, String blobName) { this(storage, container, blobName, null); }
        public BlobSource(String container, String blobName) {
            this("default", container, blobName);
        }
    }

    public record BlobOutput(String storage, String container, String prefix) {
        public BlobOutput(String container, String prefix) {
            this("default", container, prefix);
        }
    }

    /** Parse the decoded UTF-8 JSON payload, without accepting Jackson's usual scalar coercions. */
    public static ConversionJobRequest parse(String message) {
        if (message == null || message.isBlank()
                || message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw invalid("The queue message must contain JSON of at most 48 KiB.");
        }
        final JsonNode root;
        try {
            root = JSON.readTree(message);
        } catch (JsonProcessingException failure) {
            // Parser exceptions can include parts of the producer's original payload.
            throw invalid("The queue message is not valid JSON.");
        }
        if (root == null || !root.isObject()) throw invalid("message must be a JSON object.");
        JsonNode versionNode = root.get("version");
        if (versionNode == null || !versionNode.isIntegralNumber() || !versionNode.canConvertToInt()) {
            throw invalid("version must be the integer 1 or 2.");
        }
        int version = versionNode.intValue();
        if (version != 1 && version != 2) throw invalid("Only queue message versions 1 and 2 are supported.");
        object(root, "message", version == 1 ? Set.of("version", "jobId", "input", "output", "filename")
                : Set.of("version", "jobId", "input", "output", "filename", "metadata", "notification"));
        JsonNode inputNode = root.get("input");
        object(inputNode, "input", version == 1 ? Set.of("storage", "container", "blobName") : Set.of("storage", "container", "blobName", "expectedETag"));
        JsonNode outputNode = root.get("output");
        object(outputNode, "output", Set.of("storage", "container", "prefix"));
        return new ConversionJobRequest(versionNode.intValue(), requiredString(root, "jobId"),
                new BlobSource(optionalString(inputNode, "storage"), requiredString(inputNode, "container"), requiredString(inputNode, "blobName"), optionalString(inputNode, "expectedETag")),
                new BlobOutput(optionalString(outputNode, "storage"), requiredString(outputNode, "container"),
                        optionalString(outputNode, "prefix")),
                optionalString(root, "filename"), readMetadata(root.get("metadata")), readNotification(root.get("notification"))).normalized();
    }

    public String toJson() {
        try {
            ConversionJobRequest request = normalized();
            ObjectNode node = JSON.valueToTree(request);
            if (version == 1) {
                node.remove("metadata"); node.remove("notification"); ((ObjectNode) node.get("input")).remove("expectedETag");
            }
            String message = JSON.writeValueAsString(node);
            if (message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
                throw invalid("The queue message must contain JSON of at most 48 KiB.");
            }
            return message;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not serialize the queue request.", failure);
        }
    }

    /** Also validate programmatically constructed requests before persistence or publication. */
    public ConversionJobRequest normalized() {
        if (version != 1 && version != 2) {
            throw invalid("Only queue message versions 1 and 2 are supported.");
        }
        if (version == 1 && (!metadata.isEmpty() || notification != null || (input != null && input.expectedETag() != null)))
            throw invalid("Additional integration fields require queue message version 2.");
        validateMetadata(metadata);
        Notification notify = notification == null ? null : new Notification(normalizeStorageAlias(notification.queue()));
        if (notification != null && notification.queue() == null) throw invalid("notification.queue is required.");
        if (input != null && input.expectedETag() != null && (input.expectedETag().isBlank() || input.expectedETag().length() > 256
                || input.expectedETag().equals("*") || input.expectedETag().codePoints().anyMatch(Character::isISOControl)))
            throw invalid("input.expectedETag must be a nonempty ETag of at most 256 characters, not a wildcard.");
        String id = normalizeJobId(jobId);
        if (input == null || output == null) {
            throw invalid("input and output blob references are required.");
        }
        validateContainer(input.container());
        validateContainer(output.container());
        String inputStorage = normalizeStorageAlias(input.storage());
        String outputStorage = normalizeStorageAlias(output.storage());
        validateBlobPath(input.blobName(), "input.blobName", 1024, false);
        if (output.prefix() != null && output.prefix().codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("output.prefix cannot contain control characters.");
        }
        String prefix = output.prefix() == null ? "" : output.prefix().strip();
        int prefixEnd = prefix.length();
        while (prefixEnd > 0 && prefix.charAt(prefixEnd - 1) == '/') {
            prefixEnd--;
        }
        prefix = prefix.substring(0, prefixEnd);
        validateBlobPath(prefix, "output.prefix", 512, true);
        String name = filename;
        if (name == null || name.isBlank()) {
            name = input.blobName();
        }
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 255) {
            throw invalid("filename must have a valid basename of at most 255 characters.");
        }
        return new ConversionJobRequest(version, id, new BlobSource(inputStorage, input.container(), input.blobName(), input.expectedETag()),
                new BlobOutput(outputStorage, output.container(), prefix), name, new TreeMap<>(metadata), notify);
    }

    private static Map<String, String> readMetadata(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        if (!node.isObject()) throw invalid("metadata must be a flat string object.");
        Map<String, String> values = new TreeMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) throw invalid("metadata values must be strings.");
            values.put(entry.getKey(), entry.getValue().textValue());
        });
        return values;
    }
    private static Notification readNotification(JsonNode node) {
        if (node == null || node.isNull()) return null;
        object(node, "notification", Set.of("queue"));
        return new Notification(requiredString(node, "queue"));
    }
    private static void validateMetadata(Map<String, String> values) {
        if (values.size() > 16) throw invalid("metadata accepts at most 16 entries.");
        values.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 64 || key.codePoints().anyMatch(Character::isISOControl)
                    || value == null || value.length() > 512 || value.codePoints().anyMatch(Character::isISOControl))
                throw invalid("metadata keys and values must be bounded strings without control characters.");
        });
        try { if (JSON.writeValueAsBytes(values).length > 8192) throw invalid("metadata exceeds 8 KiB."); }
        catch (JsonProcessingException failure) { throw invalid("metadata could not be encoded."); }
    }

    public static String normalizeJobId(String id) {
        // UUID.fromString also accepts shortened forms such as 1-1-1-1-1.
        if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new ConversionException(400, "INVALID_JOB_ID", "The job ID must be a UUID.");
        }
        return UUID.fromString(id).toString();
    }

    private static void object(JsonNode node, String name, Set<String> fields) {
        if (node == null || !node.isObject()) {
            throw invalid(name + " must be a JSON object.");
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!fields.contains(names.next())) {
                throw invalid(name + " contains an unknown field.");
            }
        }
    }

    private static String requiredString(JsonNode node, String field) {
        String value = optionalString(node, field);
        if (value == null || value.isBlank()) {
            throw invalid(field + " must be a nonempty string.");
        }
        return value;
    }

    private static String optionalString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field + " must be a string.");
        }
        return value.textValue();
    }

    private static void validateContainer(String container) {
        if (container == null || container.length() < 3 || container.length() > 63
                || !container.matches("[a-z0-9](?:[a-z0-9]|-(?!-))*[a-z0-9]")) {
            throw invalid("Blob containers must use 3–63 lowercase letters, digits, or single hyphens, with alphanumeric ends.");
        }
    }

    private static String normalizeStorageAlias(String storage) {
        if (storage == null) {
            return "default";
        }
        if (!storage.matches("[a-z][a-z0-9_]{0,31}")) {
            throw invalid("Storage aliases must use 1–32 lowercase letters, digits, or underscores, starting with a letter.");
        }
        return storage;
    }

    private static void validateBlobPath(String path, String field, int maximum, boolean allowEmpty) {
        if (path == null || path.length() > maximum || (!allowEmpty && path.isBlank())
                || path.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(field + " is invalid or exceeds " + maximum + " characters.");
        }
        for (String segment : path.replace('\\', '/').split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw invalid(field + " cannot contain dot path segments.");
            }
        }
    }

    private static ConversionException invalid(String message) {
        return new ConversionException(400, "INVALID_QUEUE_MESSAGE", message);
    }
}
