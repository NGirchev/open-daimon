package io.github.ngirchev.opendaimon.common.ai.tool;

public record ExternalToolDescriptor(
        String name,
        String description,
        String sourceName,
        ExternalToolSourceType sourceType
) {

    public ExternalToolDescriptor(String name, String description) {
        this(name, description, null, ExternalToolSourceType.EXTERNAL);
    }

    public ExternalToolDescriptor(String name, String description, String sourceName) {
        this(name, description, sourceName, ExternalToolSourceType.EXTERNAL);
    }

    public ExternalToolDescriptor {
        sourceType = sourceType != null ? sourceType : ExternalToolSourceType.EXTERNAL;
    }
}
