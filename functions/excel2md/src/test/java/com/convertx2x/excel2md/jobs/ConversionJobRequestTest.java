package com.convertx2x.excel2md.jobs;

import com.convertx2x.excel2md.conversion.ConversionException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ConversionJobRequestTest {
    @Test void roundTripsDirectRequestAndUsesIndependentStorageRoles() {
        var request = new ConversionJobRequest(1, UUID.randomUUID().toString(),
                new ConversionJobRequest.BlobSource("source", "incoming", "日本語/台帳.xlsx"),
                new ConversionJobRequest.BlobOutput("destination", "outgoing", "customer/results/"), null).normalized();
        assertEquals("台帳.xlsx", request.filename());
        assertEquals("customer/results", request.output().prefix());
        assertEquals(request, ConversionJobRequest.parse(request.toJson()));
        var profiles = BlobStorageProfiles.from(Map.of("CONVERSION_INPUT_STORAGE_SOURCE", "secret-a",
                "CONVERSION_OUTPUT_STORAGE_DESTINATION", "secret-b"), "secret-main");
        assertEquals("secret-a", profiles.inputConnection("source"));
        assertEquals("UNKNOWN_OUTPUT_STORAGE", assertThrows(ConversionException.class,
                () -> profiles.outputConnection("source")).code());
        assertFalse(profiles.toString().contains("secret"));
    }

    @Test void rejectsUnknownFieldsCoercionDuplicatesTrailingDataAndUnsafePaths() {
        String json = request().toJson();
        for (String invalid : new String[]{
                json.replace("\"version\":1", "\"version\":\"1\""),
                json.replace("\"version\":1", "\"version\":1,\"version\":1"),
                json.substring(0, json.length()-1) + ",\"options\":{}}",
                json.replace("\"prefix\":\"\"", "\"prefix\":\"../other\""),
                json.replace("\"storage\":\"default\"", "\"storage\":\"https://example.test\""),
                json + " {}", "not json", "{}"}) {
            assertThrows(ConversionException.class, () -> ConversionJobRequest.parse(invalid), invalid);
        }
    }

    static ConversionJobRequest request() {
        return new ConversionJobRequest(1, UUID.randomUUID().toString(),
                new ConversionJobRequest.BlobSource("incoming", "input.xlsx"),
                new ConversionJobRequest.BlobOutput("outgoing", ""), "input.xlsx").normalized();
    }
}
