package com.convertx2x.office2md;

import com.convertx2x.office2md.conversion.OfficeMarkdownService;
import com.convertx2x.office2md.jobs.AzureJobService;
import com.convertx2x.office2md.jobs.JobService;

/** Shared conversion service; Storage is initialized only for an enabled asynchronous route. */
final class RuntimeServices {
    static final AppConfig CONFIG = AppConfig.fromEnvironment();
    static final OfficeMarkdownService CONVERTER = new OfficeMarkdownService(CONFIG.limits());
    private static volatile JobService instance;

    private RuntimeServices() { }

    static JobService jobs() {
        JobService current = instance;
        if (current == null) {
            synchronized (RuntimeServices.class) {
                current = instance;
                if (current == null) instance = current = new AzureJobService(CONFIG.storageConnectionString(),
                        CONVERTER, CONFIG.limits(), CONFIG.blobStorageProfiles());
            }
        }
        return current;
    }
}
