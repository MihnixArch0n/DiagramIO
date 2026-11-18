package com.diagramio.config;

import com.diagramio.domain.diagram.BlobStoragePort;
import com.diagramio.infrastructure.storage.BlobStorageProperties;
import com.diagramio.infrastructure.storage.FileSystemBlobStorageAdapter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BlobStorageProperties.class)
public class StorageConfig {

    @Bean
    public BlobStoragePort blobStoragePort(BlobStorageProperties properties) {
        return new FileSystemBlobStorageAdapter(properties);
    }
}
