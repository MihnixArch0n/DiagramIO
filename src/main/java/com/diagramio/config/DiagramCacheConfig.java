package com.diagramio.config;

import com.diagramio.domain.diagram.CachedDiagramReader;
import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.diagram.DiagramRevisionReader;
import com.diagramio.domain.diagram.DiagramReadCache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dependency injection for Cache-Aside.
 *
 * Domain remains independent of Spring and Caffeine.
 */
@Configuration
public class DiagramCacheConfig {

    @Bean
    public CachedDiagramReader cachedDiagramReader(
            DiagramRepository diagramRepository,
            DiagramRevisionReader revisionReader,
            DiagramReadCache readCache
    ) {
        return new CachedDiagramReader(
                diagramRepository,
                revisionReader,
                readCache
        );
    }
}
