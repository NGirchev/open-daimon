package io.github.ngirchev.opendaimon.common.ai.tool;

public record ExternalToolDescriptor(
        String name,
        String description,
        String sourceName
) {

    public ExternalToolDescriptor(String name, String description) {
        this(name, description, null);
    }
}
