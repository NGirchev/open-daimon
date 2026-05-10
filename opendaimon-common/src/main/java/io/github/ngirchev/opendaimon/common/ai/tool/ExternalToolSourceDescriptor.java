package io.github.ngirchev.opendaimon.common.ai.tool;

import java.util.List;

public record ExternalToolSourceDescriptor(
        String name,
        ExternalToolSourceType sourceType,
        List<ExternalToolDescriptor> tools
) {

    public ExternalToolSourceDescriptor(String name, List<ExternalToolDescriptor> tools) {
        this(name, ExternalToolSourceType.EXTERNAL, tools);
    }

    public ExternalToolSourceDescriptor {
        sourceType = sourceType != null ? sourceType : ExternalToolSourceType.EXTERNAL;
        tools = tools != null ? List.copyOf(tools) : List.of();
    }
}
