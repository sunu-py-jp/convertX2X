package com.convertx2x.office2md.ocr;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OcrSettingsTest {
    private static final String KEY = "test-only-credential";

    @Test void absentOrIncompleteConfigurationRemainsDisabled() {
        assertFalse(OcrSettings.disabled().configured());
        assertFalse(OcrSettings.from(Map.of()).configured());
        assertFalse(OcrSettings.from(Map.of(OcrSettings.ENDPOINT_SETTING, "invalid-unused-endpoint")).configured());
        assertFalse(OcrSettings.from(Map.of(OcrSettings.KEY_SETTING, KEY)).configured());
        assertFalse(OcrSettings.from(Map.of(OcrSettings.TIMEOUT_SETTING, "invalid-unused-timeout")).configured());
        assertFalse(OcrClient.disabled().configured());
        assertEquals("OCR_NOT_CONFIGURED", assertThrows(OcrException.class,
                () -> OcrClient.disabled().recognize(new byte[]{1}, "image/png")).code());
    }

    @Test void normalizesRootEndpointAndBoundsTheConfiguredTimeout() {
        OcrSettings settings = OcrSettings.from(settings("https://di.example.test/"));
        assertTrue(settings.configured());
        assertEquals(URI.create("https://di.example.test"), settings.endpoint());
        assertEquals(60, settings.timeoutSeconds());
        assertFalse(settings.toString().contains(KEY));
        Map<String, String> values = settings("https://di.example.test:443");
        values.put(OcrSettings.TIMEOUT_SETTING, "180");
        assertEquals(180, OcrSettings.from(values).timeoutSeconds());
        for (String invalid : new String[]{"0", "-1", "181", "99999999999", "1.5", "invalid"}) {
            values.put(OcrSettings.TIMEOUT_SETTING, invalid);
            assertSafeConfigurationFailure(values);
        }
    }

    @Test void configuredEndpointsCannotContainCredentialsPathsQueriesOrFragments() {
        for (String endpoint : new String[]{"http://di.example.test", "file:///tmp/image", "https://user:password@di.example.test",
                "https://di.example.test/extra", "https://di.example.test/?token=secret", "https://di.example.test/#secret",
                "https://di.example.test:0", "https://di.example.test:65536", "https://", "not a URI"}) {
            assertSafeConfigurationFailure(settings(endpoint));
        }
        Map<String, String> values = settings("https://di.example.test");
        values.put(OcrSettings.KEY_SETTING, "key\r\ninjected: value");
        assertSafeConfigurationFailure(values);
    }

    private static Map<String, String> settings(String endpoint) {
        return new HashMap<>(Map.of(OcrSettings.ENDPOINT_SETTING, endpoint, OcrSettings.KEY_SETTING, KEY));
    }

    private static void assertSafeConfigurationFailure(Map<String, String> values) {
        OcrException failure = assertThrows(OcrException.class, () -> OcrSettings.from(values));
        assertEquals("OCR_INVALID_CONFIGURATION", failure.code());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(KEY));
        assertFalse(failure.toString().contains(values.get(OcrSettings.ENDPOINT_SETTING)));
    }
}
