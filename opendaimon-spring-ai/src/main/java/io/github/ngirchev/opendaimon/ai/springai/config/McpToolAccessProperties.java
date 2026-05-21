package io.github.ngirchev.opendaimon.ai.springai.config;

import io.github.ngirchev.opendaimon.bulkhead.model.UserPriority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@ConfigurationProperties(prefix = "open-daimon.mcp.tool-access")
@Validated
@Getter
@Setter
public class McpToolAccessProperties {

    @NotEmpty(message = "tool-access.default-roles is required")
    private List<UserPriority> defaultRoles = new ArrayList<>(List.of(
            UserPriority.ADMIN,
            UserPriority.VIP,
            UserPriority.REGULAR));

    @Valid
    private List<Rule> rules = new ArrayList<>(List.of(filesystemAdminRule()));

    public boolean isAllowed(String toolName, UserPriority userPriority) {
        if (toolName == null || userPriority == null) {
            return false;
        }
        return rules.stream()
                .filter(rule -> Pattern.compile(rule.getNamePattern()).matcher(toolName).matches())
                .findFirst()
                .map(rule -> rule.getRoles().contains(userPriority))
                .orElseGet(() -> defaultRoles.contains(userPriority));
    }

    private static Rule filesystemAdminRule() {
        Rule rule = new Rule();
        rule.setNamePattern("^(?:[A-Za-z0-9_]+_)?(read_file|read_text_file|read_media_file|read_multiple_files|write_file|edit_file|create_directory|list_directory|list_directory_with_sizes|directory_tree|move_file|search_files|get_file_info|list_allowed_directories)$");
        rule.setRoles(new ArrayList<>(List.of(UserPriority.ADMIN)));
        return rule;
    }

    @Getter
    @Setter
    public static class Rule {
        @NotBlank(message = "tool-access.rules[].name-pattern is required")
        private String namePattern;

        @NotNull(message = "tool-access.rules[].roles is required")
        private List<UserPriority> roles = new ArrayList<>();
    }
}
