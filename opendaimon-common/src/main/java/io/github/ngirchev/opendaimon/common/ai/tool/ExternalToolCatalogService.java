package io.github.ngirchev.opendaimon.common.ai.tool;

import java.util.List;

public interface ExternalToolCatalogService {

    List<ExternalToolDescriptor> listAvailableTools(ExternalToolAccessContext context);
}
