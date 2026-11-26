package com.diagramio.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "diagramio.storage")
public class BlobStorageProperties {

    /**
     * Root directory on the file system where diagram payloads are stored.
     */
    private String blobDir = "data/blobs";

    public String getBlobDir() {
        return blobDir;
    }

    public void setBlobDir(String blobDir) {
        this.blobDir = blobDir;
    }
}
