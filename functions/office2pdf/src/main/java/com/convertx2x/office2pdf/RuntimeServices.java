package com.convertx2x.office2pdf;

import com.convertx2x.office2pdf.conversion.OfficePdfService;
import com.convertx2x.office2pdf.jobs.AzureJobService;
import com.convertx2x.office2pdf.jobs.JobService;

/** Shared conversion service; Storage is initialized only for an enabled asynchronous route. */
final class RuntimeServices {
    static final AppConfig CONFIG = AppConfig.fromEnvironment();
    static final OfficePdfService CONVERTER = new OfficePdfService(CONFIG.limits());
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
