package com.convertx2x.excel2img;

import com.convertx2x.excel2img.conversion.ExcelImageService;
import com.convertx2x.excel2img.jobs.AzureJobService;
import com.convertx2x.excel2img.jobs.JobService;

/** Shared conversion service; Storage is initialized only for an enabled asynchronous route. */
final class RuntimeServices {
    static final AppConfig CONFIG = AppConfig.fromEnvironment();
    static final ExcelImageService CONVERTER = new ExcelImageService(CONFIG.limits(), CONFIG.renderPolicy());
    private static volatile JobService instance;
    private RuntimeServices() { }
    static JobService jobs() {
        JobService current = instance;
        if (current == null) {
            synchronized (RuntimeServices.class) {
                current = instance;
                if (current == null) instance = current = new AzureJobService(CONFIG.integration(), CONVERTER,
                        CONFIG.limits(), CONFIG.blobStorageProfiles());
            }
        }
        return current;
    }
}
