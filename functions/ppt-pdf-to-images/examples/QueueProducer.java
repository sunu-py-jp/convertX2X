import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.queue.QueueClientBuilder;
import com.azure.storage.queue.QueueMessageEncoding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/** Upload a source file, then publish the public JSON contract directly to Queue Storage. */
public class QueueProducer {
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: QueueProducer.java <source-file> <request.json>");
            System.exit(2);
        }
        try {
            System.out.println(publish(Path.of(args[0]), Path.of(args[1])));
        } catch (Exception failure) {
            // SDK exception text can contain storage endpoints or authentication information.
            System.err.println("Queue submission failed (" + failure.getClass().getSimpleName() + ").");
            System.exit(1);
        }
    }

    public static String publish(Path sourceFile, Path requestFile) throws Exception {
        String json = Files.readString(requestFile);
        JsonNode request = new ObjectMapper().readTree(json);
        String rawId = required(request, "jobId");
        if (!rawId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("jobId must be a UUID");
        }
        String id = UUID.fromString(rawId).toString();
        if (!request.path("version").isIntegralNumber() || request.path("version").asLong() != 1) {
            throw new IllegalArgumentException("Unsupported version");
        }
        JsonNode input = request.path("input");
        String alias = input.path("storage").asText("default");
        if (!alias.matches("[a-z][a-z0-9_]{0,31}")) throw new IllegalArgumentException("Invalid storage alias");
        String controlConnection = setting("CONVERSION_STORAGE_CONNECTION_STRING");
        String inputConnection = alias.equals("default") ? controlConnection
                : setting("CONVERSION_INPUT_STORAGE_" + alias.toUpperCase(Locale.ROOT));

        var container = new BlobServiceClientBuilder().connectionString(inputConnection).buildClient()
                .getBlobContainerClient(required(input, "container"));
        container.createIfNotExists();
        // Fail on an existing path; a queued job must keep referring to the same immutable input.
        container.getBlobClient(required(input, "blobName")).uploadFromFile(sourceFile.toString(), false);

        var queue = new QueueClientBuilder().connectionString(controlConnection)
                .queueName("conversion-jobs").messageEncoding(QueueMessageEncoding.BASE64).buildClient();
        queue.createIfNotExists();
        // Send JSON as-is. The SDK performs the one Base64 encoding required by the Functions host.
        queue.sendMessage(json);
        return id;
    }

    private static String setting(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing setting: " + name);
        return value.trim();
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return value.asText();
    }
}
