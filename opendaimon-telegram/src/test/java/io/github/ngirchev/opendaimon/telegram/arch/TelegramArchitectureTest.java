package io.github.ngirchev.opendaimon.telegram.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@AnalyzeClasses(
        packages = "io.github.ngirchev.opendaimon.telegram",
        importOptions = {
                ImportOption.DoNotIncludeTests.class,
                ImportOption.DoNotIncludeJars.class
        }
)
class TelegramArchitectureTest {

    @ArchTest
    static final ArchRule telegram_uses_no_service_or_component_stereotypes =
            noClasses()
                    .should().beAnnotatedWith(Service.class)
                    .orShould().beAnnotatedWith(Component.class)
                    .because("telegram exports Spring beans through explicit configuration.");

    @ArchTest
    static final ArchRule telegram_uses_no_repository_classes =
            noClasses()
                    .that().areNotInterfaces()
                    .should().beAnnotatedWith(Repository.class)
                    .because("@Repository is only allowed on Spring Data repository interfaces.");

    @ArchTest
    static final ArchRule bean_methods_are_declared_only_in_config_packages =
            methods()
                    .that().areAnnotatedWith(Bean.class)
                    .should().beDeclaredInClassesThat().resideInAPackage("..telegram.config..")
                    .because("telegram beans must be exposed through explicit configuration classes.");

    @ArchTest
    static final ArchRule configuration_classes_are_declared_only_in_config_packages =
            classes()
                    .that().areAnnotatedWith(AutoConfiguration.class)
                    .or().areAnnotatedWith(Configuration.class)
                    .should().resideInAPackage("..telegram.config..")
                    .because("Spring configuration belongs in config packages.");

    @ArchTest
    static final ArchRule configuration_properties_are_declared_only_in_config_packages =
            classes()
                    .that().areAnnotatedWith(ConfigurationProperties.class)
                    .should().resideInAPackage("..telegram.config..")
                    .andShould().haveSimpleNameEndingWith("Properties")
                    .andShould().beAnnotatedWith(Validated.class)
                    .because("telegram configuration properties must stay validated in config packages.");

    @ArchTest
    static final ArchRule repositories_are_accessed_only_from_service_config_or_repositories =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "..telegram.config..",
                            "..telegram.repository..",
                            "..telegram.service..")
                    .should().dependOnClassesThat().resideInAPackage("..telegram.repository..")
                    .because("repository access must stay behind services and explicit configuration.");

    @ArchTest
    static final ArchRule service_layer_does_not_depend_on_handler_implementations =
            noClasses()
                    .that().resideInAPackage("..telegram.service..")
                    .should().dependOnClassesThat().resideInAPackage("..telegram.command.handler..")
                    .because("telegram services may depend on command inputs, not handler implementation details.");
}
