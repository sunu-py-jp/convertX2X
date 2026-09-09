import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.queue.QueueClientBuilder;
import com.azure.storage.queue.QueueMessageEncoding;
import com.convertx2x.excel2md.jobs.ConversionJobRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Upload an immutable Excel input, then publish the validated public Queue contract. */
public class QueueProducer {
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: QueueProducer.java <source-file> <request.json>");
            System.exit(2);
        }
        try {
            System.out.println(publish(Path.of(args[0]), Path.of(args[1])));
        } catch (Exception failure) {
            // SDK exception messages may contain connection endpoints or authentication details.
            System.err.println("Queue submission failed (" + failure.getClass().getSimpleName() + ").");
            System.exit(1);
        }
    }

    public static String publish(Path sourceFile, Path requestFile) throws Exception {
        ConversionJobRequest request = ConversionJobRequest.parse(Files.readString(requestFile));
        String controlConnection = setting("CONVERSION_STORAGE_CONNECTION_STRING");
        String alias = request.input().storage();
        String inputConnection = alias.equals("default") ? controlConnection
                : setting("CONVERSION_INPUT_STORAGE_" + alias.toUpperCase(Locale.ROOT));
        var container = new BlobServiceClientBuilder().connectionString(inputConnection).buildClient()
                .getBlobContainerClient(request.input().container());
        container.createIfNotExists();
        // Never replace the input of a previously submitted job.
        container.getBlobClient(request.input().blobName()).uploadFromFile(sourceFile.toString(), false);

        var queue = new QueueClientBuilder().connectionString(controlConnection)
                .queueName("excel2md-jobs").messageEncoding(QueueMessageEncoding.BASE64).buildClient();
        queue.createIfNotExists();
        // JSON is encoded exactly once by the SDK, matching host.json messageEncoding=base64.
        queue.sendMessage(request.toJson());
        return request.jobId();
    }

    private static String setting(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing setting: " + name);
        return value.trim();
    }
}
