package io.github.ngirchev.opendaimon.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.w3c.dom.Element;

class StarterConsumerClasspathSmokeTest {

    @Test
    void springBootDiscoversOpenDaimonAutoConfigurationsFromStarterDependency() {
        var autoConfigurations = new ArrayList<String>();
        for (String autoConfiguration : ImportCandidates.load(
                AutoConfiguration.class,
                StarterConsumerClasspathSmokeTest.class.getClassLoader())) {
            autoConfigurations.add(autoConfiguration);
        }

        assertThat(autoConfigurations)
                .contains(
                        "io.github.ngirchev.opendaimon.common.config.CoreAutoConfig",
                        "io.github.ngirchev.opendaimon.bulkhead.config.BulkHeadAutoConfig",
                        "io.github.ngirchev.opendaimon.common.storage.config.StorageAutoConfig",
                        "io.github.ngirchev.opendaimon.ai.springai.config.SpringAIAutoConfig",
                        "io.github.ngirchev.opendaimon.ai.springai.config.RAGAutoConfig",
                        "io.github.ngirchev.opendaimon.ai.springai.config.AgentAutoConfig",
                        "io.github.ngirchev.opendaimon.rest.config.RestAutoConfig");
    }

    @Test
    void exampleDeclaresStarterAndRestAsOnlyOpenDaimonDependencies() throws Exception {
        assertThat(loadOpenDaimonDependencies())
                .containsExactly(
                        "opendaimon-spring-boot-starter",
                        "opendaimon-rest");
    }

    @Test
    void applicationUsesStandardSpringBootEntryPoint() {
        assertThat(StarterConsumerApplication.class.getAnnotation(SpringBootApplication.class))
                .isNotNull();
    }

    @Test
    void starterClasspathIncludesFlywayPostgreSqlSupport() throws Exception {
        assertThat(Class.forName("org.flywaydb.database.postgresql.PostgreSQLDatabaseType"))
                .isNotNull();
    }

    @Test
    void starterDefaultsAreAvailableFromConsumerClasspath() throws Exception {
        var loader = new YamlPropertySourceLoader();
        var starterDefaults = loader
                .load(
                        "opendaimon-defaults.yml",
                        new ClassPathResource("META-INF/opendaimon/opendaimon-defaults.yml"))
                .getFirst();

        assertThat(starterDefaults.getProperty("open-daimon.ai.spring-ai.enabled"))
                .isEqualTo(true);
        assertThat(starterDefaults.containsProperty("open-daimon.rest.enabled"))
                .isFalse();
        assertThat(starterDefaults.getProperty("spring.ai.openai.base-url"))
                .isEqualTo("https://openrouter.ai/api");
        assertThat(starterDefaults.getProperty("open-daimon.ai.spring-ai.models.list[0].provider-type"))
                .isEqualTo("${OPENDAIMON_DEFAULT_PROVIDER:OPENAI}");
    }

    @Test
    void exampleApplicationExplicitlyEnablesRestModule() throws Exception {
        var loader = new YamlPropertySourceLoader();
        var application = loader
                .load(
                        "application.yml",
                        new ClassPathResource("application.yml"))
                .getFirst();

        assertThat(application.getProperty("open-daimon.rest.enabled"))
                .isEqualTo(true);
    }

    private List<String> loadOpenDaimonDependencies() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

        var document = factory.newDocumentBuilder()
                .parse(Path.of("pom.xml").toFile());
        var dependencies = document.getElementsByTagName("dependency");
        var openDaimonDependencies = new ArrayList<String>();

        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dependency = (Element) dependencies.item(i);
            String artifactId = textOf(dependency, "artifactId");
            if ("io.github.ngirchev".equals(textOf(dependency, "groupId"))
                    && artifactId.startsWith("opendaimon-")) {
                openDaimonDependencies.add(artifactId);
            }
        }

        return openDaimonDependencies;
    }

    private String textOf(Element element, String tagName) {
        return element.getElementsByTagName(tagName)
                .item(0)
                .getTextContent()
                .trim();
    }
}
