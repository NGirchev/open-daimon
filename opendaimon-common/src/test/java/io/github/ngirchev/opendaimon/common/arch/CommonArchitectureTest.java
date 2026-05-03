package io.github.ngirchev.opendaimon.common.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(
        packages = {
                "io.github.ngirchev.opendaimon.common",
                "io.github.ngirchev.opendaimon.bulkhead"
        },
        importOptions = {
                ImportOption.DoNotIncludeTests.class,
                ImportOption.DoNotIncludeJars.class
        }
)
class CommonArchitectureTest {

    private static final String[] COMMON_MODULE_PACKAGES = {
            "io.github.ngirchev.opendaimon.common..",
            "io.github.ngirchev.opendaimon.bulkhead.."
    };

    private static final String[] DOWNSTREAM_MODULE_PACKAGES = {
            "io.github.ngirchev.opendaimon.ai.springai..",
            "io.github.ngirchev.opendaimon.ai.ui..",
            "io.github.ngirchev.opendaimon.telegram..",
            "io.github.ngirchev.opendaimon.rest.."
    };

    private static final String[] COMMON_CONFIG_PACKAGES = {
            "io.github.ngirchev.opendaimon.common.config..",
            "io.github.ngirchev.opendaimon.common.storage.config..",
            "io.github.ngirchev.opendaimon.bulkhead.config.."
    };

    private static final ArchCondition<JavaClass> HAVE_COMMON_CONFIGURATION_PREFIX =
            new ArchCondition<>("have an open-daimon.common configuration prefix") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    ConfigurationProperties annotation = item.getAnnotationOfType(ConfigurationProperties.class);
                    String prefix = annotation.prefix().isBlank() ? annotation.value() : annotation.prefix();
                    if (!prefix.startsWith("open-daimon.common")) {
                        events.add(SimpleConditionEvent.violated(
                                item,
                                item.getName() + " uses configuration prefix '" + prefix + "'"));
                    }
                }
            };

    private static final SliceAssignment COMMON_RUNTIME_SLICES = new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
            String packageName = javaClass.getPackageName();
            if (packageName.startsWith("io.github.ngirchev.opendaimon.bulkhead")) {
                return SliceIdentifier.of("bulkhead");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.common.agent")) {
                return SliceIdentifier.of("agent");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.common.ai")) {
                return SliceIdentifier.of("ai");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.common.command")) {
                return SliceIdentifier.of("command");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.common.meter")) {
                return SliceIdentifier.of("meter");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.common.model")) {
                return SliceIdentifier.of("model");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.common.repository")) {
                return SliceIdentifier.of("repository");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.common.service")) {
                return SliceIdentifier.of("service");
            }
            if (packageName.startsWith("io.github.ngirchev.opendaimon.common.storage")) {
                return SliceIdentifier.of("storage");
            }
            return SliceIdentifier.ignore();
        }

        @Override
        public String getDescription() {
            return "common runtime slices";
        }
    };

    @ArchTest
    static final ArchRule common_module_uses_no_service_or_component_stereotypes =
            noClasses()
                    .that().resideInAnyPackage(COMMON_MODULE_PACKAGES)
                    .should().beAnnotatedWith(Service.class)
                    .orShould().beAnnotatedWith(Component.class)
                    .because("common exports Spring beans through explicit auto-configuration.");

    @ArchTest
    static final ArchRule common_module_uses_no_repository_classes =
            noClasses()
                    .that().resideInAnyPackage(COMMON_MODULE_PACKAGES)
                    .and().areNotInterfaces()
                    .should().beAnnotatedWith(Repository.class)
                    .because("@Repository is only allowed on Spring Data repository interfaces.");

    @ArchTest
    static final ArchRule common_module_defines_no_delivery_controllers =
            noClasses()
                    .that().resideInAnyPackage(COMMON_MODULE_PACKAGES)
                    .should().beAnnotatedWith(Controller.class)
                    .orShould().beAnnotatedWith(RestController.class)
                    .orShould().beAnnotatedWith(ControllerAdvice.class)
                    .because("common is a base library, not a delivery module.");

    @ArchTest
    static final ArchRule common_module_does_not_depend_on_downstream_modules =
            noClasses()
                    .that().resideInAnyPackage(COMMON_MODULE_PACKAGES)
                    .should().dependOnClassesThat().resideInAnyPackage(DOWNSTREAM_MODULE_PACKAGES)
                    .because("opendaimon-common is the base library and must not depend on delivery or AI modules.");

    @ArchTest
    static final ArchRule common_runtime_slices_have_no_cycles =
            slices().assignedFrom(COMMON_RUNTIME_SLICES)
                    .should().beFreeOfCycles()
                    .because("common package slices should stay independently understandable and reusable.");

    @ArchTest
    static final ArchRule repositories_are_interfaces =
            classes()
                    .that().resideInAnyPackage(COMMON_MODULE_PACKAGES)
                    .and().haveSimpleNameEndingWith("Repository")
                    .should().beInterfaces()
                    .because("common repositories are Spring Data interfaces, not concrete infrastructure classes.");

    @ArchTest
    static final ArchRule bean_methods_are_declared_only_in_config_packages =
            methods()
                    .that().areAnnotatedWith(Bean.class)
                    .should().beDeclaredInClassesThat().resideInAnyPackage(COMMON_CONFIG_PACKAGES)
                    .because("common beans must be exposed through explicit configuration classes.");

    @ArchTest
    static final ArchRule configuration_classes_are_declared_only_in_config_packages =
            classes()
                    .that().areAnnotatedWith(AutoConfiguration.class)
                    .or().areAnnotatedWith(Configuration.class)
                    .should().resideInAnyPackage(COMMON_CONFIG_PACKAGES)
                    .because("Spring configuration belongs in config packages.");

    @ArchTest
    static final ArchRule configuration_properties_follow_common_conventions =
            classes()
                    .that().areAnnotatedWith(ConfigurationProperties.class)
                    .should().resideInAnyPackage(COMMON_CONFIG_PACKAGES)
                    .andShould().haveSimpleNameEndingWith("Properties")
                    .andShould().beAnnotatedWith(Validated.class)
                    .andShould(HAVE_COMMON_CONFIGURATION_PREFIX)
                    .because("common configuration properties must stay validated and under open-daimon.common.");

    @ArchTest
    static final ArchRule repositories_are_accessed_only_from_service_config_or_repositories =
            noClasses()
                    .that().resideInAnyPackage(COMMON_MODULE_PACKAGES)
                    .and().resideOutsideOfPackages(
                            "io.github.ngirchev.opendaimon.common.config..",
                            "io.github.ngirchev.opendaimon.common.repository..",
                            "io.github.ngirchev.opendaimon.common.service..")
                    .should().dependOnClassesThat().resideInAPackage("io.github.ngirchev.opendaimon.common.repository..")
                    .because("repository access must stay behind services and explicit auto-configuration.");
}
