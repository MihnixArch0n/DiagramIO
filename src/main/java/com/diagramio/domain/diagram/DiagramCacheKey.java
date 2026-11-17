package com.diagramio.domain.diagram;

import java.util.Objects;
import java.util.UUID;

/**
 * Cache key gồm diagram ID và phiên bản hiện hành.
 *
 * Mỗi lần JPA tăng @Version, cache key thay đổi.
 * Không lưu share token hoặc thông tin phân quyền vào key.
 */
public record DiagramCacheKey(
        UUID diagramId,
        int version
) {
    public DiagramCacheKey {
        Objects.requireNonNull(
                diagramId,
                "diagramId must not be null"
        );

        if (version < 0) {
            throw new IllegalArgumentException(
                    "version must not be negative"
            );
        }
    }
}
