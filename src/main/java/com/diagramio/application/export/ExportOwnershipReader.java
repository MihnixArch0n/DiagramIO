
package com.diagramio.application.export;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface ExportOwnershipReader {

    Optional<Boolean> isOwner(UUID diagramId, UUID ownerId);
}
