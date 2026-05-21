package io.github.ngirchev.opendaimon.common.ai.tool;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;

public record ExternalToolAccessContext(
        Long userId,
        UserPriority userPriority
) {
}
