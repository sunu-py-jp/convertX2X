package com.convertx2x.excel2img;

import com.convertx2x.excel2img.conversion.RenderPolicy;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {
    @Test void readsEveryRenderPolicySettingWithoutEnablingStorage() {
        var config = AppConfig.from(Map.of(
                "CONVERSION_RENDER_MAX_WIDTH_PIXELS", "1200", "CONVERSION_RENDER_MAX_HEIGHT_PIXELS", "1600",
                "CONVERSION_RENDER_MIN_TEXT_PIXELS", "14", "CONVERSION_RENDER_PIXELS_PER_POINT", "1.5",
                "CONVERSION_RENDER_MAX_CELLS", "10000", "CONVERSION_RENDER_MAX_SOURCE_IMAGE_PIXELS", "8000000"));
        assertEquals(new RenderPolicy(1200, 1600, 14, 1.5, 10000, 8000000), config.renderPolicy());
        assertFalse(config.asyncEnabled());
        assertEquals(RenderPolicy.defaults(), AppConfig.from(Map.of()).renderPolicy());
    }

    @Test void rejectsNonfiniteAndNonpositivePolicyValues() {
        for (String value : new String[]{"NaN", "Infinity", "0", "-1"})
            assertThrows(IllegalArgumentException.class, () -> AppConfig.from(Map.of("CONVERSION_RENDER_MIN_TEXT_PIXELS", value)));
        assertThrows(IllegalArgumentException.class, () -> AppConfig.from(Map.of("CONVERSION_RENDER_MAX_WIDTH_PIXELS", "0")));
    }
}
