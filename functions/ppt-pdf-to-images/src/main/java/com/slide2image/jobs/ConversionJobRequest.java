package com.slide2image.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.slide2image.conversion.ConversionException;
import com.slide2image.conversion.ConversionOptions;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.UUID;

/** A versioned queue request. Storage names refer to server-configured aliases, never credentials or URLs. */
public record ConversionJobRequest(int version, String jobId, BlobSource input, BlobOutput output,
                                   String filename, ConversionOptions options, Map<String, String> metadata, Notification notification) {
    public ConversionJobRequest(int version, String jobId, BlobSource input, BlobOutput output,
            String filename, ConversionOptions options) {
        this(version, jobId, input, output, filename, options, Map.of(), null);
    }
    public ConversionJobRequest { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    public record Notification(String queue) {}
    public static final int VERSION = 1;
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

    public record BlobOutput(String storage, String container, String prefix, String mode, String naming) {
        public BlobOutput(String storage, String container, String prefix, String mode) { this(storage, container, prefix, mode, "padded"); }
        public BlobOutput {
            // Also preserve equality with persisted requests created before mode was introduced.
            mode = mode == null ? "zip" : mode;
            naming = naming == null ? "padded" : naming;
        }

        public BlobOutput(String storage, String container, String prefix) {
            this(storage, container, prefix, "zip");
        }

        public BlobOutput(String container, String prefix) {
            this("default", container, prefix, "zip");
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
        if (root == null || !root.isObject()) throw invalid("message must be an object.");
        JsonNode versionNode = root.get("version");
        if (versionNode == null || !versionNode.isIntegralNumber() || !versionNode.canConvertToInt()) {
            throw invalid("version must be the integer 1 or 2.");
        }
        boolean v2 = versionNode.intValue() == 2;
        object(root, "message", v2 ? Set.of("version", "jobId", "input", "output", "filename", "options", "metadata", "notification")
                : Set.of("version", "jobId", "input", "output", "filename", "options"));
        JsonNode inputNode = root.get("input");
        object(inputNode, "input", v2 ? Set.of("storage", "container", "blobName", "expectedETag") : Set.of("storage", "container", "blobName"));
        JsonNode outputNode = root.get("output");
        object(outputNode, "output", v2 ? Set.of("storage", "container", "prefix", "mode", "naming") : Set.of("storage", "container", "prefix", "mode"));
        Map<String, String> metadata = new TreeMap<>();
        JsonNode meta = root.get("metadata");
        if (meta != null) {
            if (!meta.isObject()) throw invalid("metadata must be a flat string map.");
            meta.fields().forEachRemaining(e -> {
                if (!e.getValue().isTextual()) throw invalid("metadata values must be strings.");
                metadata.put(e.getKey(), e.getValue().textValue());
            });
        }
        Notification notification = null;
        JsonNode notify = root.get("notification");
        if (notify != null && !notify.isNull()) {
            object(notify, "notification", Set.of("queue"));
            notification = new Notification(requiredString(notify, "queue"));
        }
        ConversionOptions options = null;
        JsonNode optionsNode = root.get("options");
        if (optionsNode != null) {
            object(optionsNode, "options", Set.of("width", "format", "page"));
            options = new ConversionOptions(optionalPositiveInteger(optionsNode, "width"),
                    optionalString(optionsNode, "format"), optionalPositiveInteger(optionsNode, "page"));
        }
        return new ConversionJobRequest(versionNode.intValue(), requiredString(root, "jobId"),
                new BlobSource(optionalString(inputNode, "storage"), requiredString(inputNode, "container"), requiredString(inputNode, "blobName"), optionalString(inputNode, "expectedETag")),
                new BlobOutput(optionalString(outputNode, "storage"), requiredString(outputNode, "container"),
                        optionalString(outputNode, "prefix"), optionalString(outputNode, "mode"), optionalString(outputNode, "naming")),
                optionalString(root, "filename"), options, metadata, notification).normalized();
    }

    public String toJson() {
        try {
            ConversionJobRequest normalized = normalized();
            ObjectNode wire = JSON.valueToTree(normalized);
            if (version == 1) {
                wire.remove("metadata"); wire.remove("notification");
                ((ObjectNode) wire.get("input")).remove("expectedETag");
                ((ObjectNode) wire.get("output")).remove("naming");
            }
            String message = JSON.writeValueAsString(wire);
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
        if (version == 1 && (!metadata.isEmpty() || notification != null
                || (input != null && input.expectedETag() != null)
                || (output != null && !"padded".equals(output.naming())))) {
            throw invalid("The requested fields require queue message version 2.");
        }
        if (metadata.size() > 16) throw invalid("metadata supports at most 16 entries.");
        for (var entry : metadata.entrySet()) {
            if (entry.getKey().isBlank() || entry.getKey().length() > 64 || entry.getValue().length() > 512
                    || entry.getKey().codePoints().anyMatch(Character::isISOControl)
                    || entry.getValue().codePoints().anyMatch(Character::isISOControl)) {
                throw invalid("metadata keys and values exceed the supported size or contain control characters.");
            }
        }
        try {
            if (JSON.writeValueAsBytes(metadata).length > 8192) throw invalid("metadata exceeds 8 KiB.");
        } catch (JsonProcessingException failure) { throw invalid("metadata is invalid."); }
        if (notification != null) {
            if (notification.queue() == null) throw invalid("notification.queue must be a registered alias.");
            normalizeStorageAlias(notification.queue());
        }
        String id = normalizeJobId(jobId);
        if (input == null || output == null) {
            throw invalid("input and output blob references are required.");
        }
        if (input.expectedETag() != null && (input.expectedETag().isBlank() || input.expectedETag().length() > 256
                || input.expectedETag().equals("*") || input.expectedETag().codePoints().anyMatch(Character::isISOControl)))
            throw invalid("input.expectedETag must be a specific ETag of at most 256 characters.");
        if (!Set.of("padded", "page-number").contains(output.naming())
                || (!output.mode().equals("images") && !output.naming().equals("padded")))
            throw invalid("output.naming must be padded, or page-number for images mode.");
        validateContainer(input.container());
        validateContainer(output.container());
        String inputStorage = normalizeStorageAlias(input.storage());
        String outputStorage = normalizeStorageAlias(output.storage());
        if (!output.mode().equals("zip") && !output.mode().equals("images")) {
            throw invalid("output.mode must be zip or images.");
        }
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
        ConversionOptions selected = options == null ? new ConversionOptions(null, "png", null) : options;
        if ((selected.width() != null && selected.width() <= 0)
                || (selected.page() != null && selected.page() <= 0)) {
            throw invalid("width and page must be positive integers when provided.");
        }
        String format = selected.format() == null ? "png" : selected.format().strip().toLowerCase(Locale.ROOT);
        if (!format.equals("png") && !format.equals("jpeg")) {
            throw invalid("options.format must be png or jpeg.");
        }
        return new ConversionJobRequest(version, id, new BlobSource(inputStorage, input.container(), input.blobName(), input.expectedETag()),
                new BlobOutput(outputStorage, output.container(), prefix, output.mode(), output.naming()), name,
                new ConversionOptions(selected.width(), format, selected.page()), metadata, notification);
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

    private static Integer optionalPositiveInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw invalid("options." + field + " must be a positive integer or null.");
        }
        return value.intValue();
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
