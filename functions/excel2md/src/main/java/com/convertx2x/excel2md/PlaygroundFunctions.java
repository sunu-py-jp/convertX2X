package com.convertx2x.excel2md;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Serves the small, same-origin playground directly from the application JAR. */
public class PlaygroundFunctions {
    private static final Map<String, String> ASSETS = Map.of(
            "style.css", "text/css; charset=utf-8",
            "app.js", "text/javascript; charset=utf-8");
    private static final String CONTENT_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' blob:; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
    private final AppConfig config;

    public PlaygroundFunctions() {
        this(AppConfig.fromEnvironment());
    }

    PlaygroundFunctions(AppConfig config) {
        this.config = config;
    }

    @FunctionName("Playground")
    public HttpResponseMessage page(
            @HttpTrigger(name = "request", methods = HttpMethod.GET, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "playground") HttpRequestMessage<Optional<String>> request) {
        String path = request.getUri().getRawPath();
        if (path.endsWith("/")) {
            // Canonical path keeps relative assets valid; no query-string keys are copied into the URL.
            return response(request, HttpStatus.FOUND)
                    .header("Location", path.replaceFirst("^/+", "/").replaceFirst("/+$", "")).build();
        }
        return resource(request, "index.html", "text/html; charset=utf-8");
    }

    @FunctionName("PlaygroundAsset")
    public HttpResponseMessage asset(
            @HttpTrigger(name = "request", methods = HttpMethod.GET, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "playground/assets/{name}") HttpRequestMessage<Optional<String>> request,
            @BindingName("name") String name) {
        String contentType = name == null ? null : ASSETS.get(name);
        if (contentType == null) {
            return response(request, HttpStatus.NOT_FOUND).header("Content-Type", "text/plain; charset=utf-8")
                    .body("Asset not found").build();
        }
        return resource(request, name, contentType);
    }

    @FunctionName("PlaygroundConfig")
    public HttpResponseMessage configuration(
            @HttpTrigger(name = "request", methods = HttpMethod.GET, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "playground/config") HttpRequestMessage<Optional<String>> request) {
        // Explicit public fields only. Never serialize AppConfig, which contains a connection string.
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("asyncEnabled", config.asyncEnabled());
        options.put("supportedFormats", List.of("xlsx", "xls"));
        options.put("maxInputBytes", config.limits().maxInputBytes());
        options.put("maxSheets", config.limits().maxSheets());
        options.put("maxReadCells", config.limits().maxReadCells());
        options.put("maxTableCells", config.limits().maxTableCells());
        options.put("maxMarkdownBytes", config.limits().maxMarkdownBytes());
        options.put("maxImages", config.limits().maxImages());
        options.put("maxImageBytes", config.limits().maxImageBytes());
        options.put("maxOutputBytes", config.limits().maxOutputBytes());
        options.put("maxShapes", config.limits().maxShapes());
        options.put("maxGroupDepth", config.limits().maxGroupDepth());
        options.put("maxImagePixels", config.limits().maxImagePixels());
        try {
            return response(request, HttpStatus.OK).header("Content-Type", "application/json; charset=utf-8")
                    .body(new ObjectMapper().writeValueAsString(options)).build();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize public playground settings", exception);
        }
    }

    @FunctionName("Capabilities")
    public HttpResponseMessage capabilities(
            @HttpTrigger(name = "request", methods = HttpMethod.GET, authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "capabilities") HttpRequestMessage<Optional<String>> request) {
        return configuration(request);
    }

    private static HttpResponseMessage resource(HttpRequestMessage<?> request, String name, String type) {
        try (InputStream stream = PlaygroundFunctions.class.getResourceAsStream("/playground/" + name)) {
            if (stream == null) throw new IOException("Missing playground resource");
            return response(request, HttpStatus.OK).header("Content-Type", type).body(stream.readAllBytes()).build();
        } catch (IOException exception) {
            return response(request, HttpStatus.INTERNAL_SERVER_ERROR)
                    .header("Content-Type", "text/plain; charset=utf-8").body("Playground assets are unavailable").build();
        }
    }

    private static HttpResponseMessage.Builder response(HttpRequestMessage<?> request, HttpStatus status) {
        return request.createResponseBuilder(status).header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff").header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", CONTENT_POLICY);
    }
}
