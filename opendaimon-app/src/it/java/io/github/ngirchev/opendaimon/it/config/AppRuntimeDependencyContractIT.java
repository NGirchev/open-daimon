package io.github.ngirchev.opendaimon.it.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that runtime-only application composition declares the companion
 * dependencies needed by optional library modules.
 */
class AppRuntimeDependencyContractIT {

    @Test
    @DisplayName("opendaimon-app keeps MinIO runtime dependencies on the packaged classpath")
    void minioRuntimeDependency_hasOkHttpRuntimeCompanion() throws Exception {
        Document pom = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(Path.of("pom.xml").toFile());

        Optional<String> minioScope = dependencyScope(pom, "io.minio", "minio");
        Optional<String> okhttpScope = dependencyScope(pom, "com.squareup.okhttp3", "okhttp");

        assertThat(minioScope)
                .as("opendaimon-app must include MinIO for runtime storage auto-configuration")
                .hasValueSatisfying(scope -> assertThat(isRuntimeVisible(scope)).isTrue());

        assertThat(okhttpScope)
                .as("MinIO constructs MinioClient with okhttp3.RequestBody on the runtime classpath")
                .hasValueSatisfying(scope -> assertThat(isRuntimeVisible(scope)).isTrue());
    }

    @Test
    @DisplayName("opendaimon-app packages Spring AI provider auto-configurations")
    void springAiRuntimeDependency_hasProviderAutoconfigCompanions() throws Exception {
        List<String> packagedLibraries = packagedLibraries();

        assertThat(packagedLibraries)
                .as("OpenAI/OpenRouter models require OpenAiChatAutoConfiguration to create OpenAiChatModel")
                .anyMatch(name -> name.startsWith("spring-ai-autoconfigure-model-openai-"));
        assertThat(packagedLibraries)
                .as("Ollama models require OllamaChatAutoConfiguration to create OllamaChatModel")
                .anyMatch(name -> name.startsWith("spring-ai-autoconfigure-model-ollama-"));
    }

    private static Optional<String> dependencyScope(Document pom, String groupId, String artifactId) {
        NodeList dependencies = pom.getElementsByTagName("dependency");
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            if (groupId.equals(text(dependency, "groupId"))
                    && artifactId.equals(text(dependency, "artifactId"))) {
                return Optional.ofNullable(text(dependency, "scope"));
            }
        }
        return Optional.empty();
    }

    private static boolean isRuntimeVisible(String scope) {
        return scope == null || scope.isBlank() || "compile".equals(scope) || "runtime".equals(scope);
    }

    private static String text(Element element, String tagName) {
        NodeList nodes = element.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent().trim();
    }

    private static List<String> packagedLibraries() throws Exception {
        Path jarPath;
        try (var jars = Files.list(Path.of("target"))) {
            jarPath = jars
                    .filter(path -> path.getFileName().toString().matches("opendaimon-app-[^-].*\\.jar"))
                    .filter(path -> !path.getFileName().toString().contains("-sources"))
                    .filter(path -> !path.getFileName().toString().contains("-javadoc"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Packaged opendaimon-app jar was not found"));
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return jar.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.startsWith("BOOT-INF/lib/"))
                    .map(name -> name.substring("BOOT-INF/lib/".length()))
                    .toList();
        }
    }
}
