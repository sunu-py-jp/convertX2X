package com.convertx2x.excel2md;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import com.convertx2x.excel2md.conversion.*;
import com.convertx2x.excel2md.jobs.JobDownload;
import com.convertx2x.excel2md.jobs.JobService;
import com.convertx2x.excel2md.jobs.JobStatus;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Transport adapters; both HTTP and Queue use the same ExcelMarkdownService instance. */
public class ConversionFunctions {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AppConfig config;
    private final ExcelMarkdownService converter;
    private final Supplier<JobService> jobs;

    public ConversionFunctions() {
        this(RuntimeServices.CONFIG, RuntimeServices.CONVERTER, RuntimeServices::jobs);
    }

    ConversionFunctions(AppConfig config, ExcelMarkdownService converter, Supplier<JobService> jobs) {
        this.config = config;
        this.converter = converter;
        this.jobs = jobs;
    }

    @FunctionName("ConvertHttp")
    public HttpResponseMessage convert(
            @HttpTrigger(name = "request", methods = HttpMethod.POST, authLevel = AuthorizationLevel.FUNCTION,
                    route = "convert", dataType = "binary") HttpRequestMessage<Optional<byte[]>> request,
            ExecutionContext context) {
        return handle(request, context, () -> {
            Upload upload = upload(request);
            try (ConversionResult result = converter.convert(upload.bytes(), upload.filename())) {
                return binary(request, new JobDownload(result.zipBytes(), "application/zip", "document.zip"))
                        .header("X-Sheet-Count", Integer.toString(result.sheetCount()))
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
            String statusUrl = jobPath(request, job.id(), false);
            return json(request, HttpStatus.ACCEPTED, statusBody(request, job))
                    .header("Location", statusUrl).header("Retry-After", "3").build();
        });
    }

    @FunctionName("GetConversion")
    public HttpResponseMessage status(
            @HttpTrigger(name = "request", methods = HttpMethod.GET, authLevel = AuthorizationLevel.FUNCTION,
                    route = "jobs/{id}") HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id, ExecutionContext context) {
        return handle(request, context, () -> {
            requireAsync();
            JobStatus job = jobs.get().find(id).orElseThrow(() ->
                    new ConversionException(404, "JOB_NOT_FOUND", "Job was not found"));
            return json(request, HttpStatus.OK, statusBody(request, job)).header("Retry-After", "3").build();
        });
    }

    @FunctionName("DownloadConversion")
    public HttpResponseMessage download(
            @HttpTrigger(name = "request", methods = HttpMethod.GET, authLevel = AuthorizationLevel.FUNCTION,
                    route = "jobs/{id}/result") HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id, ExecutionContext context) {
        return handle(request, context, () -> {
            requireAsync();
            return binary(request, jobs.get().download(id, "document.md")).build();
        });
    }

    @FunctionName("DownloadReport")
    public HttpResponseMessage report(
            @HttpTrigger(name = "request", methods = HttpMethod.GET, authLevel = AuthorizationLevel.FUNCTION,
                    route = "jobs/{id}/report") HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id, ExecutionContext context) {
        return handle(request, context, () -> {
            requireAsync();
            return binary(request, jobs.get().download(id, "report.json")).build();
        });
    }

    @FunctionName("DownloadAsset")
    public HttpResponseMessage asset(
            @HttpTrigger(name = "request", methods = HttpMethod.GET, authLevel = AuthorizationLevel.FUNCTION,
                    route = "jobs/{id}/images/{assetName}") HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id, @BindingName("assetName") String assetName, ExecutionContext context) {
        return handle(request, context, () -> {
            requireAsync();
            if (assetName == null || !assetName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}") || assetName.contains("..")) {
                throw new ConversionException(404, "ARTIFACT_NOT_FOUND", "The result artifact was not found.");
            }
            return binary(request, jobs.get().download(id, "images/" + assetName)).build();
        });
    }

    @FunctionName("DownloadArchive")
    public HttpResponseMessage archive(
            @HttpTrigger(name = "request", methods = HttpMethod.GET, authLevel = AuthorizationLevel.FUNCTION,
                    route = "jobs/{id}/archive") HttpRequestMessage<Optional<String>> request,
            @BindingName("id") String id, ExecutionContext context) {
        return handle(request, context, () -> {
            requireAsync();
            return binary(request, jobs.get().archive(id)).build();
        });
    }

    @FunctionName("ProcessConversion")
    public void process(
            @QueueTrigger(name = "message", queueName = "excel2md-jobs", connection = AppConfig.QUEUE_CONNECTION_SETTING)
                    String message, ExecutionContext context) {
        requireAsync();
        try {
            jobs.get().process(message);
        } catch (RuntimeException failure) {
            logQueueFailure(context, failure);
            // Host diagnostics must not expose SDK URLs, credentials or raw queue JSON.
            throw new IllegalStateException("Queue conversion failed; the message will follow the retry policy.");
        }
    }

    @FunctionName("PoisonConversion")
    public void poison(
            @QueueTrigger(name = "message", queueName = "excel2md-jobs-poison", connection = AppConfig.QUEUE_CONNECTION_SETTING)
                    String message, ExecutionContext context) {
        requireAsync();
        try {
            jobs.get().poison(message);
        } catch (RuntimeException failure) {
            logQueueFailure(context, failure);
            throw new IllegalStateException("Queue failure handling failed; retry is required.");
        }
    }

    private static void logQueueFailure(ExecutionContext context, RuntimeException failure) {
        String code = failure instanceof ConversionException conversion ? "; code=" + conversion.code() : "";
        context.getLogger().warning("Queue request failed: " + failure.getClass().getSimpleName()
                + code + "; invocation=" + context.getInvocationId());
    }

    private Upload upload(HttpRequestMessage<Optional<byte[]>> request) {
        String contentType = header(request, "Content-Type");
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            throw new ConversionException(415, "RAW_BODY_REQUIRED",
                    "Send the file bytes directly as the request body, not multipart/form-data");
        }
        byte[] bytes = request.getBody().orElseThrow(() ->
                new ConversionException(400, "EMPTY_INPUT", "A file is required in the request body"));
        if (bytes.length == 0) throw new ConversionException(400, "EMPTY_INPUT", "The file is empty");
        if (bytes.length > config.limits().maxInputBytes()) {
            throw new ConversionException(413, "INPUT_TOO_LARGE", "The file exceeds the input size limit");
        }
        Map<String, String> query = request.getQueryParameters();
        String filename = query.getOrDefault("filename", header(request, "x-file-name"));
        if (filename == null || filename.isBlank()) filename = "workbook.xlsx";
        filename = filename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (filename.isBlank() || filename.equals(".") || filename.equals("..")
                || filename.length() > 200 || filename.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new ConversionException(400, "INVALID_FILENAME", "The filename is invalid");
        }
        return new Upload(bytes, filename);
    }

    private void requireAsync() {
        if (!config.asyncEnabled()) {
            throw new ConversionException(503, "ASYNC_DISABLED",
                    "Asynchronous conversion is disabled; configure " + AppConfig.STORAGE_SETTING);
        }
    }

    private static String header(HttpRequestMessage<?> request, String name) {
        return request.getHeaders().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static HttpResponseMessage.Builder binary(HttpRequestMessage<?> request, JobDownload result) {
        String safeFilename = result.filename().replaceAll("[^A-Za-z0-9._-]", "_");
        return request.createResponseBuilder(HttpStatus.OK).header("Content-Type", result.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + safeFilename + "\"")
                .header("X-Content-Type-Options", "nosniff").header("Cache-Control", "no-store")
                .body(result.bytes());
    }

    private static Map<String, Object> statusBody(HttpRequestMessage<?> request, JobStatus job) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job", job);
        body.put("statusUrl", jobPath(request, job.id(), false));
        if ("succeeded".equals(job.status())) {
            String path = jobPath(request, job.id(), false);
            body.put("resultUrl", path + "/result");
            body.put("reportUrl", path + "/report");
            body.put("archiveUrl", path + "/archive");
            body.put("assetsBaseUrl", path + "/images/");
        }
        return body;
    }

    private static String jobPath(HttpRequestMessage<?> request, String id, boolean result) {
        // Relative URLs retain a configured route prefix without trusting Host or exposing ?code= keys.
        String path = request.getUri().getPath();
        int index = path.lastIndexOf("/jobs");
        String prefix = index >= 0 ? path.substring(0, index) : "/api";
        return prefix + "/jobs/" + id + (result ? "/result" : "");
    }

    private static HttpResponseMessage.Builder json(HttpRequestMessage<?> request, HttpStatus status, Object body) {
        try {
            return request.createResponseBuilder(status).header("Content-Type", "application/json; charset=utf-8")
                    .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
                    .body(JSON.writeValueAsString(body));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Response serialization failed", ex);
        }
    }

    private static HttpResponseMessage handle(HttpRequestMessage<?> request, ExecutionContext context,
                                               Supplier<HttpResponseMessage> action) {
        try {
            return action.get();
        } catch (ConversionException ex) {
            HttpResponseMessage.Builder response = json(request, HttpStatus.valueOf(ex.statusCode()),
                    Map.of("error", Map.of("code", ex.code(), "message", ex.getMessage())));
            if (ex.statusCode() == 503) response.header("Retry-After", "3");
            return response.build();
        } catch (RuntimeException ex) {
            // SDK exception messages can contain URLs or connection information. Log type + invocation only.
            context.getLogger().severe("Conversion request failed: " + ex.getClass().getSimpleName()
                    + "; invocation=" + context.getInvocationId());
            return json(request, HttpStatus.INTERNAL_SERVER_ERROR,
                    Map.of("error", Map.of("code", "INTERNAL_ERROR", "message", "The request could not be completed")))
                    .build();
        }
    }

    private record Upload(byte[] bytes, String filename) { }
}
