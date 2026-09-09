package com.convertx2x.excel2md.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.convertx2x.excel2md.conversion.ConversionException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** A versioned queue request. Storage names refer to server-configured aliases, never credentials or URLs. */
public record ConversionJobRequest(int version, String jobId, BlobSource input, BlobOutput output,
                                   String filename) {
    public static final int VERSION = 1;
    public static final int MAX_MESSAGE_BYTES = 48 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public record BlobSource(String storage, String container, String blobName) {
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
        object(root, "message", Set.of("version", "jobId", "input", "output", "filename"));
        JsonNode versionNode = root.get("version");
        if (versionNode == null || !versionNode.isIntegralNumber() || !versionNode.canConvertToInt()) {
            throw invalid("version must be the integer 1.");
        }
        JsonNode inputNode = root.get("input");
        object(inputNode, "input", Set.of("storage", "container", "blobName"));
        JsonNode outputNode = root.get("output");
        object(outputNode, "output", Set.of("storage", "container", "prefix"));
        return new ConversionJobRequest(versionNode.intValue(), requiredString(root, "jobId"),
                new BlobSource(optionalString(inputNode, "storage"), requiredString(inputNode, "container"), requiredString(inputNode, "blobName")),
                new BlobOutput(optionalString(outputNode, "storage"), requiredString(outputNode, "container"),
                        optionalString(outputNode, "prefix")),
                optionalString(root, "filename")).normalized();
    }

    public String toJson() {
        try {
            String message = JSON.writeValueAsString(normalized());
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
        if (version != VERSION) {
            throw invalid("Only queue message version 1 is supported.");
        }
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
        return new ConversionJobRequest(VERSION, id, new BlobSource(inputStorage, input.container(), input.blobName()),
                new BlobOutput(outputStorage, output.container(), prefix), name);
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
