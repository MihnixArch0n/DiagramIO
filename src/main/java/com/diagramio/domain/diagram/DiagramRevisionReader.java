package com.diagramio.domain.diagram;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * Read-only port for obtaining the current diagram version.
 *
 * The implementation must query persistent storage.
 * It must not return a version from the content cache.
 */
public interface DiagramRevisionReader {

    OptionalInt findCurrentVersion(UUID diagramId);
}
