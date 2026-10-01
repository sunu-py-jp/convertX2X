package com.convertx2x.office2pdf;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Serves a same-origin PDF conversion playground directly from the application JAR. */
public class PlaygroundFunctions {
    private static final Map<String, String> ASSETS = Map.of("style.css", "text/css; charset=utf-8",
            "app.js", "text/javascript; charset=utf-8");
    private static final String POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "connect-src 'self'; frame-src 'self' blob:; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
    private final AppConfig config;
    public PlaygroundFunctions() { this(AppConfig.fromEnvironment()); }
    PlaygroundFunctions(AppConfig config) { this.config = config; }

    @FunctionName("Playground")
    public HttpResponseMessage page(@HttpTrigger(name = "request", methods = HttpMethod.GET,
            authLevel = AuthorizationLevel.ANONYMOUS, route = "playground") HttpRequestMessage<Optional<String>> request) {
        String path = request.getUri().getRawPath();
        if (path.endsWith("/")) return response(request, HttpStatus.FOUND)
                .header("Location", path.replaceFirst("^/+", "/").replaceFirst("/+$", "")).build();
        return resource(request, "index.html", "text/html; charset=utf-8");
    }

    @FunctionName("PlaygroundAsset")
    public HttpResponseMessage asset(@HttpTrigger(name = "request", methods = HttpMethod.GET,
            authLevel = AuthorizationLevel.ANONYMOUS, route = "playground/assets/{name}") HttpRequestMessage<Optional<String>> request,
            @BindingName("name") String name) {
        String type = name == null ? null : ASSETS.get(name);
        if (type == null) return response(request, HttpStatus.NOT_FOUND).body("Asset not found").build();
        return resource(request, name, type);
    }

    @FunctionName("PlaygroundConfig")
    public HttpResponseMessage configuration(@HttpTrigger(name = "request", methods = HttpMethod.GET,
            authLevel = AuthorizationLevel.ANONYMOUS, route = "playground/config") HttpRequestMessage<Optional<String>> request) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("asyncEnabled", config.asyncEnabled());
        values.put("supportedFormats", List.of("xlsx", "xls", "docx", "pptx", "ppt"));
        values.put("maxInputBytes", config.limits().maxInputBytes());
        values.put("maxOutputBytes", config.limits().maxOutputBytes());
        values.put("maxPages", config.limits().maxPages());
        values.put("maxImagePixels", config.limits().maxImagePixels());
        try { return response(request, HttpStatus.OK).header("Content-Type", "application/json; charset=utf-8")
                .body(new ObjectMapper().writeValueAsString(values)).build(); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Could not serialize settings", failure); }
    }

    @FunctionName("Capabilities")
    public HttpResponseMessage capabilities(@HttpTrigger(name = "request", methods = HttpMethod.GET,
            authLevel = AuthorizationLevel.ANONYMOUS, route = "capabilities") HttpRequestMessage<Optional<String>> request) {
        return configuration(request);
    }

    private static HttpResponseMessage resource(HttpRequestMessage<?> request, String name, String type) {
        try (InputStream stream = PlaygroundFunctions.class.getResourceAsStream("/playground/" + name)) {
            if (stream == null) throw new IOException("Missing resource");
            return response(request, HttpStatus.OK).header("Content-Type", type).body(stream.readAllBytes()).build();
        } catch (IOException failure) {
            return response(request, HttpStatus.INTERNAL_SERVER_ERROR).body("Playground assets are unavailable").build();
        }
    }

    private static HttpResponseMessage.Builder response(HttpRequestMessage<?> request, HttpStatus status) {
        return request.createResponseBuilder(status).header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff").header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", POLICY);
    }
}
