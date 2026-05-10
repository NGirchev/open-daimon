package io.github.ngirchev.opendaimon.common.ai.tool;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public interface ExternalToolCatalogService {

    List<ExternalToolDescriptor> listAvailableTools(ExternalToolAccessContext context);

    default List<ExternalToolSourceDescriptor> listAvailableToolSources(ExternalToolAccessContext context) {
        Map<String, List<ExternalToolDescriptor>> toolsBySource = listAvailableTools(context).stream()
                .collect(Collectors.groupingBy(
                        ExternalToolCatalogService::sourceKey,
                        java.util.LinkedHashMap::new,
                        Collectors.toList()));
        return toolsBySource.entrySet().stream()
                .map(entry -> new ExternalToolSourceDescriptor(
                        sourceName(entry.getValue().getFirst()),
                        entry.getValue().getFirst().sourceType(),
                        entry.getValue()))
                .toList();
    }

    private static String sourceKey(ExternalToolDescriptor tool) {
        return tool.sourceType().name() + "\u0000" + sourceName(tool);
    }

    private static String sourceName(ExternalToolDescriptor tool) {
        return tool.sourceName() != null && !tool.sourceName().isBlank()
                ? tool.sourceName()
                : "external";
    }
}
