package io.github.ngirchev.opendaimon.common.agent;

/**
 * Outcome of a single tool execution within the agent loop.
 *
 * @param toolName name of the tool that was invoked
 * @param result   tool output (serialized as string)
 * @param success  whether the tool executed without errors
 * @param error    error message if execution failed (null on success)
 */
public record AgentToolResult(
        String toolName,
        String result,
        boolean success,
        String error
) {

    public static AgentToolResult success(String toolName, String result) {
        return new AgentToolResult(toolName, result, true, null);
    }

    public static AgentToolResult failure(String toolName, String error) {
        return new AgentToolResult(toolName, null, false, error);
    }
}
