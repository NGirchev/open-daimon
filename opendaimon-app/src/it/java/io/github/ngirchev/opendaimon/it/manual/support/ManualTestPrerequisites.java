package io.github.ngirchev.opendaimon.it.manual.support;

import io.github.ngirchev.dotenv.DotEnvLoader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assumptions;

public final class ManualTestPrerequisites {

    private ManualTestPrerequisites() {
    }

    public static void requireOpenRouterKey() {
        DotEnvLoader.loadDotEnv(Path.of("../.env"));
        String openRouterKey = System.getProperty("OPENROUTER_KEY", System.getenv("OPENROUTER_KEY"));
        Assumptions.assumeTrue(
                openRouterKey != null && !openRouterKey.isBlank() && !openRouterKey.equals("sk-placeholder"),
                "Skipping manual test: OPENROUTER_KEY not set in .env or environment"
        );
    }

    public static void requireSerperKey() {
        DotEnvLoader.loadDotEnv(Path.of("../.env"));
        String serperKey = System.getProperty("SERPER_KEY", System.getenv("SERPER_KEY"));
        Assumptions.assumeTrue(
                serperKey != null && !serperKey.isBlank(),
                "Skipping manual test: SERPER_KEY not set in .env or environment"
        );
    }

    public static void requireLocalOllamaWithModels(List<String> requiredModels, Duration timeout) {
        String baseUrl = resolveOllamaBaseUrl();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .timeout(timeout)
                .uri(URI.create(baseUrl + "/api/tags"))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean statusOk = response.statusCode() == 200;
            boolean modelsPresent = requiredModels.stream().allMatch(response.body()::contains);
            Assumptions.assumeTrue(statusOk && modelsPresent,
                    "Skipping manual test: Ollama/models unavailable at " + baseUrl + ". Required: " + requiredModels);
        } catch (Exception ex) {
            Assumptions.assumeTrue(false,
                    "Skipping manual test: cannot connect to Ollama at " + baseUrl + ". " + ex.getMessage());
        }
    }

    public static String resolveOllamaBaseUrl() {
        String baseUrl = System.getenv("OLLAMA_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:11434";
        }
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }
}
