package com.convertx2x.office2md;

import com.convertx2x.office2md.conversion.OfficeMarkdownService;
import com.convertx2x.office2md.jobs.AzureJobService;
import com.convertx2x.office2md.jobs.JobService;
import com.convertx2x.office2md.ocr.DocumentIntelligenceOcrClient;

/** Shared conversion service; Storage is initialized only for an enabled asynchronous route. */
final class RuntimeServices {
    static final AppConfig CONFIG = AppConfig.fromEnvironment();
    static final OfficeMarkdownService CONVERTER = new OfficeMarkdownService(CONFIG.limits(),
            new DocumentIntelligenceOcrClient(CONFIG.ocr()));
    private static volatile JobService instance;

    private RuntimeServices() { }

    static JobService jobs() {
        JobService current = instance;
        if (current == null) {
            synchronized (RuntimeServices.class) {
                current = instance;
                if (current == null) instance = current = new AzureJobService(CONFIG.integration(),
                        CONVERTER, CONFIG.limits(), CONFIG.blobStorageProfiles());
            }
        }
        return current;
    }
}
