package io.github.ngirchev.opendaimon.common.ai.tool;

import java.util.List;

public record ExternalToolSourceDescriptor(
        String name,
        List<ExternalToolDescriptor> tools
) {

    public ExternalToolSourceDescriptor {
        tools = tools != null ? List.copyOf(tools) : List.of();
    }
}
