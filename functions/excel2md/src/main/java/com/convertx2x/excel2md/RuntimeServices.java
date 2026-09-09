package com.convertx2x.excel2md;

import com.convertx2x.excel2md.conversion.ExcelMarkdownService;
import com.convertx2x.excel2md.jobs.AzureJobService;
import com.convertx2x.excel2md.jobs.JobService;

/** Shared conversion service; Storage is initialized only for an enabled asynchronous route. */
final class RuntimeServices {
    static final AppConfig CONFIG = AppConfig.fromEnvironment();
    static final ExcelMarkdownService CONVERTER = new ExcelMarkdownService(CONFIG.limits());
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
