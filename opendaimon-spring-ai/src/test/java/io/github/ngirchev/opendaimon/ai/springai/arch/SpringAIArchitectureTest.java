package io.github.ngirchev.opendaimon.ai.springai.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@AnalyzeClasses(
        packages = "io.github.ngirchev.opendaimon.ai.springai",
        importOptions = {
                ImportOption.DoNotIncludeTests.class,
                ImportOption.DoNotIncludeJars.class
        }
)
class SpringAIArchitectureTest {

    private static final SliceAssignment SPRING_AI_RUNTIME_SLICES = new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
            String packageName = javaClass.getPackageName();
            if (packageName.startsWith("io.github.ngirchev.opendaimon.ai.springai.advisor")) {
                return SliceIdentifier.of("advisor");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.ai.springai.agent")) {
                return SliceIdentifier.of("agent");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.ai.springai.embedding")) {
                return SliceIdentifier.of("embedding");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.ai.springai.memory")) {
                return SliceIdentifier.of("memory");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.ai.springai.rag")) {
                return SliceIdentifier.of("rag");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.ai.springai.rest")) {
                return SliceIdentifier.of("rest-client");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.ai.springai.retry")) {
                return SliceIdentifier.of("retry");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.ai.springai.service")) {
                return SliceIdentifier.of("service");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.ai.springai.tool")) {
                return SliceIdentifier.of("tool");
            }
            return SliceIdentifier.ignore();
        }

        @Override
        public String getDescription() {
            return "spring-ai runtime slices";
        }
    };

    @ArchTest
    static final ArchRule spring_ai_uses_no_service_or_component_stereotypes =
            noClasses()
                    .should().beAnnotatedWith(Service.class)
                    .orShould().beAnnotatedWith(Component.class)
                    .because("spring-ai exports Spring beans through explicit auto-configuration.");

    @ArchTest
    static final ArchRule spring_ai_uses_no_repository_classes =
            noClasses()
                    .that().areNotInterfaces()
                    .should().beAnnotatedWith(Repository.class)
                    .because("@Repository is only allowed on Spring Data repository interfaces.");

    @ArchTest
    static final ArchRule bean_methods_are_declared_only_in_config_packages =
            methods()
                    .that().areAnnotatedWith(Bean.class)
                    .should().beDeclaredInClassesThat().resideInAPackage("..springai.config..")
                    .because("spring-ai beans must be exposed through explicit auto-configuration classes.");

    @ArchTest
    static final ArchRule configuration_classes_are_declared_only_in_config_packages =
            classes()
                    .that().areAnnotatedWith(AutoConfiguration.class)
                    .or().areAnnotatedWith(Configuration.class)
                    .should().resideInAPackage("..springai.config..")
                    .because("Spring configuration belongs in config packages.");

    @ArchTest
    static final ArchRule configuration_properties_are_declared_only_in_config_packages =
            classes()
                    .that().areAnnotatedWith(ConfigurationProperties.class)
                    .should().resideInAPackage("..springai.config..")
                    .andShould().haveSimpleNameEndingWith("Properties")
                    .andShould().beAnnotatedWith(Validated.class)
                    .because("spring-ai configuration properties must stay validated in config packages.");

    @ArchTest
    static final ArchRule runtime_slices_have_no_cycles =
            slices().assignedFrom(SPRING_AI_RUNTIME_SLICES)
                    .should().beFreeOfCycles()
                    .because("spring-ai runtime packages should stay independently understandable.");
}
