package io.github.ngirchev.opendaimon.common.ai.tool;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public interface ExternalToolCatalogService {

    List<ExternalToolDescriptor> listAvailableTools(ExternalToolAccessContext context);

    default List<ExternalToolSourceDescriptor> listAvailableToolSources(ExternalToolAccessContext context) {
        Map<String, List<ExternalToolDescriptor>> toolsBySource = listAvailableTools(context).stream()
                .collect(Collectors.groupingBy(
                        ExternalToolCatalogService::sourceName,
                        java.util.LinkedHashMap::new,
                        Collectors.toList()));
        return toolsBySource.entrySet().stream()
                .map(entry -> new ExternalToolSourceDescriptor(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static String sourceName(ExternalToolDescriptor tool) {
        return tool.sourceName() != null && !tool.sourceName().isBlank()
                ? tool.sourceName()
                : "external";
    }
}
