package com.convertx2x.md2pdf;

import com.convertx2x.md2pdf.conversion.ConversionException;
import com.convertx2x.md2pdf.conversion.ConversionResult;
import com.convertx2x.md2pdf.conversion.MarkdownPdfService;
import com.convertx2x.md2pdf.jobs.JobDownload;
import com.convertx2x.md2pdf.jobs.JobService;
import com.convertx2x.md2pdf.jobs.JobStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.microsoft.azure.functions.annotation.QueueTrigger;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** HTTP and Queue adapters using the same pure-Java MarkdownPdfService. */
public class ConversionFunctions {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AppConfig config;
    private final MarkdownPdfService converter;
    private final Supplier<JobService> jobs;

    public ConversionFunctions() { this(RuntimeServices.CONFIG, RuntimeServices.CONVERTER, RuntimeServices::jobs); }
    ConversionFunctions(AppConfig config, MarkdownPdfService converter, Supplier<JobService> jobs) {
        this.config = config; this.converter = converter; this.jobs = jobs;
    }

    @FunctionName("ConvertHttp")
    public HttpResponseMessage convert(
            @HttpTrigger(name = "request", methods = HttpMethod.POST, authLevel = AuthorizationLevel.FUNCTION,
                    route = "convert", dataType = "binary") HttpRequestMessage<Optional<byte[]>> request,
            ExecutionContext context) {
        return handle(request, context, () -> {
            Upload upload = upload(request);
            try (ConversionResult result = converter.convert(upload.bytes(), upload.filename())) {
                return binary(request, new JobDownload(result.pdfBytes(), "application/pdf", outputName(upload.filename())))
                        .header("X-Page-Count", Integer.toString(result.pageCount()))
                        .header("X-Section-Count", Integer.toString(result.sectionCount()))
                        .header("X-Warning-Count", Integer.toString(result.warningCount())).build();
            }
        });
    }

    @FunctionName("SubmitConversion")
    public HttpResponseMessage submit(
            @HttpTrigger(name = "request", methods = HttpMethod.POST, authLevel = AuthorizationLevel.FUNCTION,
                    route = "jobs", dataType = "binary") HttpRequestMessage<Optional<byte[]>> request,
            ExecutionContext context) {
        return handle(request, context, () -> {
            requireAsync();
            Upload upload = upload(request);
            JobStatus job = jobs.get().submit(upload.bytes(), upload.filename());
            String statusUrl = jobPath(request, job.id());
            return json(request, HttpStatus.ACCEPTED, statusBody(request, job)).header("Location", statusUrl)
                    .header("Retry-After", "3").build();
        });
    }

    @FunctionName("GetConversion")
    public HttpResponseMessage status(
            @HttpTrigger(name = "request", methods = HttpMethod.GET, authLevel = AuthorizationLevel.FUNCTION,
                    route = "jobs/{id}") HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id, ExecutionContext context) {
        return handle(request, context, () -> {
            requireAsync();
            JobStatus job = jobs.get().find(id).orElseThrow(() -> new ConversionException(404, "JOB_NOT_FOUND", "Job was not found."));
            return json(request, HttpStatus.OK, statusBody(request, job)).header("Retry-After", "3").build();
        });
    }

    @FunctionName("DownloadConversion")
    public HttpResponseMessage download(
            @HttpTrigger(name = "request", methods = HttpMethod.GET, authLevel = AuthorizationLevel.FUNCTION,
                    route = "jobs/{id}/result") HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id, ExecutionContext context) {
        return handle(request, context, () -> { requireAsync(); return binary(request, jobs.get().download(id, "document.pdf")).build(); });
    }

    @FunctionName("DownloadReport")
    public HttpResponseMessage report(
            @HttpTrigger(name = "request", methods = HttpMethod.GET, authLevel = AuthorizationLevel.FUNCTION,
                    route = "jobs/{id}/report") HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id, ExecutionContext context) {
        return handle(request, context, () -> { requireAsync(); return binary(request, jobs.get().download(id, "report.json")).build(); });
    }

    @FunctionName("DownloadArchive")
    public HttpResponseMessage archive(
            @HttpTrigger(name = "request", methods = HttpMethod.GET, authLevel = AuthorizationLevel.FUNCTION,
                    route = "jobs/{id}/archive") HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id, ExecutionContext context) {
        return handle(request, context, () -> { requireAsync(); return binary(request, jobs.get().archive(id)).build(); });
    }

    @FunctionName("ProcessConversion")
    public void process(@QueueTrigger(name = "message", queueName = "md2pdf-jobs",
            connection = AppConfig.QUEUE_CONNECTION_SETTING) String message, ExecutionContext context) {
        requireAsync();
        try { jobs.get().process(message); }
        catch (RuntimeException failure) { logQueueFailure(context, failure); throw new IllegalStateException("Queue conversion failed; the message will follow the retry policy."); }
    }

    @FunctionName("PoisonConversion")
    public void poison(@QueueTrigger(name = "message", queueName = "md2pdf-jobs-poison",
            connection = AppConfig.QUEUE_CONNECTION_SETTING) String message, ExecutionContext context) {
        requireAsync();
        try { jobs.get().poison(message); }
        catch (RuntimeException failure) { logQueueFailure(context, failure); throw new IllegalStateException("Queue failure handling failed; retry is required."); }
    }

    @FunctionName("MaintainConversions")
    public void maintenance(@com.microsoft.azure.functions.annotation.TimerTrigger(name = "timer", schedule = "0 */5 * * * *")
                            String timer, ExecutionContext context) {
        if (!config.asyncEnabled() || (config.integration().resultQueues().isEmpty()
                && config.integration().resultRetentionDays() == 0)) return;
        try { jobs.get().maintenance(); }
        catch (RuntimeException failure) { logQueueFailure(context, failure); throw new IllegalStateException("Conversion maintenance failed; a later timer run will retry."); }
    }

    private Upload upload(HttpRequestMessage<Optional<byte[]>> request) {
        String type = header(request, "Content-Type");
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/"))
            throw new ConversionException(415, "RAW_BODY_REQUIRED", "Send the file bytes directly, not multipart/form-data.");
        byte[] bytes = request.getBody().orElseThrow(() -> new ConversionException(400, "EMPTY_INPUT", "A file is required."));
        if (bytes.length == 0) throw new ConversionException(400, "EMPTY_INPUT", "The file is empty.");
        if (bytes.length > config.limits().maxInputBytes()) throw new ConversionException(413, "INPUT_TOO_LARGE", "The file exceeds the input size limit.");
        String filename = request.getQueryParameters().getOrDefault("filename", header(request, "x-file-name"));
        if (filename == null || filename.isBlank()) throw new ConversionException(400, "MISSING_FILENAME", "filename is required.");
        filename = filename.replace('\\', '/'); filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (filename.isBlank() || filename.equals(".") || filename.equals("..") || filename.length() > 200
                || filename.chars().anyMatch(character -> character < 32 || character == 127))
            throw new ConversionException(400, "INVALID_FILENAME", "The filename is invalid.");
        return new Upload(bytes, filename);
    }

    private void requireAsync() {
        if (!config.asyncEnabled()) throw new ConversionException(503, "ASYNC_DISABLED",
                "Asynchronous conversion is disabled; configure " + AppConfig.STORAGE_SETTING + ".");
    }

    private static Map<String, Object> statusBody(HttpRequestMessage<?> request, JobStatus job) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job", job); String path = jobPath(request, job.id()); body.put("statusUrl", path);
        if ("succeeded".equals(job.status())) {
            body.put("resultUrl", path + "/result"); body.put("reportUrl", path + "/report"); body.put("archiveUrl", path + "/archive");
        }
        return body;
    }

    private static String jobPath(HttpRequestMessage<?> request, String id) {
        String path = request.getUri().getPath(); int index = path.lastIndexOf("/jobs");
        return (index >= 0 ? path.substring(0, index) : "/api") + "/jobs/" + id;
    }

    private static HttpResponseMessage.Builder binary(HttpRequestMessage<?> request, JobDownload result) {
        String safe = result.filename().replaceAll("[^A-Za-z0-9._-]", "_");
        return request.createResponseBuilder(HttpStatus.OK).header("Content-Type", result.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + safe + "\"")
                .header("X-Content-Type-Options", "nosniff").header("Cache-Control", "no-store").body(result.bytes());
    }

    private static HttpResponseMessage.Builder json(HttpRequestMessage<?> request, HttpStatus status, Object body) {
        try { return request.createResponseBuilder(status).header("Content-Type", "application/json; charset=utf-8")
                .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff").body(JSON.writeValueAsString(body)); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Response serialization failed", failure); }
    }

    private static HttpResponseMessage handle(HttpRequestMessage<?> request, ExecutionContext context,
                                              Supplier<HttpResponseMessage> action) {
        try { return action.get(); }
        catch (ConversionException failure) {
            HttpResponseMessage.Builder response = json(request, HttpStatus.valueOf(failure.statusCode()),
                    Map.of("error", Map.of("code", failure.code(), "message", failure.getMessage())));
            if (failure.statusCode() == 503) response.header("Retry-After", "3");
            return response.build();
        } catch (RuntimeException failure) {
            context.getLogger().severe("Conversion request failed: " + failure.getClass().getSimpleName()
                    + "; invocation=" + context.getInvocationId());
            return json(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    Map.of("error", Map.of("code", "INTERNAL_ERROR", "message", "The request could not be completed."))).build();
        }
    }

    private static void logQueueFailure(ExecutionContext context, RuntimeException failure) {
        String code = failure instanceof ConversionException converted ? "; code=" + converted.code() : "";
        context.getLogger().warning("Queue request failed: " + failure.getClass().getSimpleName() + code
                + "; invocation=" + context.getInvocationId());
    }

    private static String header(HttpRequestMessage<?> request, String name) {
        return request.getHeaders().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static String outputName(String input) {
        int dot = input.lastIndexOf('.'); String base = dot > 0 ? input.substring(0, dot) : input;
        base = base.replaceAll("[^A-Za-z0-9_-]", "_"); return (base.isBlank() ? "document" : base) + ".pdf";
    }

    private record Upload(byte[] bytes, String filename) { }
}
