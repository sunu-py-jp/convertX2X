package com.slide2image.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.slide2image.conversion.ConversionException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlobStorageProfilesTest {
    private static final String DEFAULT = "AccountName=defaultaccount;AccountKey=default-secret-key";
    private static final String SOURCE = "AccountName=sourceaccount;AccountKey=source-secret-key";
    private static final String ARCHIVE = "AccountName=archiveaccount;AccountKey=archive-secret-key";

    @Test
    void defaultConnectionIsAvailableForBothRolesAndIsTrimmed() {
        BlobStorageProfiles profiles = BlobStorageProfiles.from(Map.of(), " \n" + DEFAULT + "\t ");
        assertEquals(DEFAULT, profiles.inputConnection("default"));
        assertEquals(DEFAULT, profiles.outputConnection("default"));
    }

    @Test
    void namedConnectionsAreResolvedOnlyForTheirRegisteredRole() {
        BlobStorageProfiles profiles = BlobStorageProfiles.from(Map.of(
                "CONVERSION_INPUT_STORAGE_SOURCE", " " + SOURCE + " ",
                "CONVERSION_OUTPUT_STORAGE_ARCHIVE", "\n" + ARCHIVE + "\n"), DEFAULT);
        assertEquals(SOURCE, profiles.inputConnection("source"));
        assertEquals(ARCHIVE, profiles.outputConnection("archive"));
        unknownOutput(profiles, "source");
        unknownInput(profiles, "archive");
        unknownInput(profiles, "SOURCE");
        unknownOutput(profiles, "ARCHIVE");
    }

    @Test
    void sameAliasMayHaveSeparateConnectionsForTheTwoRoles() {
        BlobStorageProfiles profiles = BlobStorageProfiles.from(Map.of(
                "CONVERSION_INPUT_STORAGE_SHARED", SOURCE,
                "CONVERSION_OUTPUT_STORAGE_SHARED", ARCHIVE), DEFAULT);
        assertEquals(SOURCE, profiles.inputConnection("shared"));
        assertEquals(ARCHIVE, profiles.outputConnection("shared"));
    }

    @Test
    void missingNullAndBlankSettingsDoNotRegisterAliases() {
        Map<String, String> settings = new HashMap<>();
        settings.put("CONVERSION_INPUT_STORAGE_NULL_VALUE", null);
        settings.put("CONVERSION_INPUT_STORAGE_EMPTY", "");
        settings.put("CONVERSION_OUTPUT_STORAGE_BLANK", " \t\n");
        for (String defaultConnection : new String[] {null, "", " \t\n"}) {
            BlobStorageProfiles profiles = BlobStorageProfiles.from(settings, defaultConnection);
            unknownInput(profiles, "default");
            unknownOutput(profiles, "default");
            unknownInput(profiles, "null_value");
            unknownInput(profiles, "empty");
            unknownOutput(profiles, "blank");
            unknownInput(profiles, "missing");
        }
    }

    @Test
    void unrelatedEnvironmentVariablesAreIgnored() {
        BlobStorageProfiles profiles = BlobStorageProfiles.from(Map.of(
                "AzureWebJobsStorage", SOURCE,
                "CONVERSION_STORAGE_CONNECTION_STRING", DEFAULT,
                "OTHER_OUTPUT_STORAGE_ARCHIVE", ARCHIVE), null);
        unknownInput(profiles, "default");
        unknownOutput(profiles, "archive");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEFAULT", "", "default", "Source", "SOURCE-NAME", "0SOURCE", "_SOURCE",
            "SOURCE/SECRET", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456"})
    void rejectsReservedOrInvalidEnvironmentSuffixesWithoutExposingTheirValue(String suffix) {
        for (String prefix : new String[] {"CONVERSION_INPUT_STORAGE_", "CONVERSION_OUTPUT_STORAGE_"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> BlobStorageProfiles.from(Map.of(prefix + suffix, SOURCE), DEFAULT));
            assertFalse(failure.getMessage().contains(SOURCE));
            assertFalse(failure.getMessage().contains("source-secret-key"));
            assertFalse(failure.getMessage().contains("SOURCE/SECRET"));
            assertNull(failure.getCause());
        }
    }

    @Test
    void aliasesAllowTheFullDocumentedLowercasePatternAfterEnvironmentNormalization() {
        String alias = "a".repeat(32);
        BlobStorageProfiles profiles = BlobStorageProfiles.from(Map.of(
                "CONVERSION_INPUT_STORAGE_A_B1", SOURCE,
                "CONVERSION_OUTPUT_STORAGE_" + alias.toUpperCase(java.util.Locale.ROOT), ARCHIVE), null);
        assertEquals(SOURCE, profiles.inputConnection("a_b1"));
        assertEquals(ARCHIVE, profiles.outputConnection(alias));
    }

    @Test
    void configurationSnapshotDoesNotChangeWhenTheInputMapChanges() {
        Map<String, String> settings = new HashMap<>();
        settings.put("CONVERSION_INPUT_STORAGE_SOURCE", SOURCE);
        BlobStorageProfiles profiles = BlobStorageProfiles.from(settings, DEFAULT);
        settings.put("CONVERSION_INPUT_STORAGE_SOURCE", ARCHIVE);
        settings.put("CONVERSION_OUTPUT_STORAGE_ARCHIVE", ARCHIVE);
        assertEquals(SOURCE, profiles.inputConnection("source"));
        unknownOutput(profiles, "archive");
    }

    @Test
    void toStringShowsAliasesWithoutExposingConnectionsOrAccountKeys() {
        BlobStorageProfiles profiles = BlobStorageProfiles.from(Map.of(
                "CONVERSION_INPUT_STORAGE_SOURCE", SOURCE,
                "CONVERSION_OUTPUT_STORAGE_ARCHIVE", ARCHIVE), DEFAULT);
        String rendered = profiles.toString();
        assertTrue(rendered.contains("default"));
        assertTrue(rendered.contains("source"));
        assertTrue(rendered.contains("archive"));
        assertFalse(rendered.contains("AccountName"));
        assertFalse(rendered.contains("AccountKey"));
        assertFalse(rendered.contains("secret-key"));
        assertFalse(rendered.contains(DEFAULT));
        assertFalse(rendered.contains(SOURCE));
        assertFalse(rendered.contains(ARCHIVE));
    }

    @Test
    void unknownAliasErrorsDoNotEchoUntrustedAliasValues() {
        BlobStorageProfiles profiles = BlobStorageProfiles.from(Map.of(), DEFAULT);
        String untrustedAlias = "AccountKey=untrusted-secret";
        ConversionException input = unknownInput(profiles, untrustedAlias);
        ConversionException output = unknownOutput(profiles, untrustedAlias);
        assertFalse(input.getMessage().contains(untrustedAlias));
        assertFalse(output.getMessage().contains(untrustedAlias));
        assertNull(input.getCause());
        assertNull(output.getCause());
    }

    private static ConversionException unknownInput(BlobStorageProfiles profiles, String alias) {
        ConversionException failure = assertThrows(ConversionException.class, () -> profiles.inputConnection(alias));
        assertEquals(400, failure.statusCode());
        assertEquals("UNKNOWN_INPUT_STORAGE", failure.code());
        return failure;
    }

    private static ConversionException unknownOutput(BlobStorageProfiles profiles, String alias) {
        ConversionException failure = assertThrows(ConversionException.class, () -> profiles.outputConnection(alias));
        assertEquals(400, failure.statusCode());
        assertEquals("UNKNOWN_OUTPUT_STORAGE", failure.code());
        return failure;
    }
}
