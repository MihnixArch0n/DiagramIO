package com.diagramio.domain.diagram;

import java.util.Optional;

/**
 * Domain port for storing and retrieving diagram payload blobs (Claim-Check pattern).
 * Pure Java interface to preserve Clean Architecture domain independence.
 */
public interface BlobStoragePort {

    /**
     * Stores the string content under the given storage key atomically.
     */
    void store(String key, String content);

    /**
     * Retrieves the string content for the given storage key, if it exists.
     */
    Optional<String> load(String key);

    /**
     * Deletes the blob corresponding to the specified key.
     */
    void delete(String key);

    /**
     * Deletes all blobs under a given key prefix (e.g. all blobs belonging to a diagram).
     */
    void deleteAllPrefix(String prefix);
}
