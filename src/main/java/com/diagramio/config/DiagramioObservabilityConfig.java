package com.diagramio.config;

import com.diagramio.infrastructure.cache.CaffeineDiagramReadCache;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers live Caffeine statistics without caching credentials or request data. */
@Configuration
public class DiagramioObservabilityConfig {

    @Bean
    public MeterBinder diagramioCacheMeterBinder(CaffeineDiagramReadCache cache) {
        return registry -> {
            Gauge.builder("diagramio.cache.hit.ratio", cache, CaffeineDiagramReadCache::hitRate)
                    .description("Cache hits divided by cache lookups (1 before first lookup)")
                    .register(registry);
            Gauge.builder("diagramio.cache.entries", cache, CaffeineDiagramReadCache::estimatedSize)
                    .description("Estimated count of entries retained in Caffeine")
                    .register(registry);
            FunctionCounter.builder("diagramio.cache.evictions", cache, CaffeineDiagramReadCache::evictionCount)
                    .description("Caffeine evictions since application start")
                    .register(registry);
        };
    }
}
