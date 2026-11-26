package com.diagramio.config;

import com.diagramio.domain.diagram.DiagramCommandService;
import com.diagramio.domain.diagram.DiagramQueryService;
import com.diagramio.domain.diagram.DiagramRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DiagramConfig {

    @Bean
    public DiagramCommandService diagramCommandService(DiagramRepository diagramRepository) {
        return new DiagramCommandService(diagramRepository);
    }

    @Bean
    public DiagramQueryService diagramQueryService(DiagramRepository diagramRepository) {
        return new DiagramQueryService(diagramRepository);
    }
}
