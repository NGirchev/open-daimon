package io.github.ngirchev.opendaimon.rest.arch;

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
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@AnalyzeClasses(
        packages = "io.github.ngirchev.opendaimon.rest",
        importOptions = {
                ImportOption.DoNotIncludeTests.class,
                ImportOption.DoNotIncludeJars.class
        }
)
class RestArchitectureTest {

    @ArchTest
    static final ArchRule rest_uses_no_service_or_component_stereotypes =
            noClasses()
                    .should().beAnnotatedWith(Service.class)
                    .orShould().beAnnotatedWith(Component.class)
                    .because("rest exports Spring beans through explicit configuration.");

    @ArchTest
    static final ArchRule rest_uses_no_repository_classes =
            noClasses()
                    .that().areNotInterfaces()
                    .should().beAnnotatedWith(Repository.class)
                    .because("@Repository is only allowed on Spring Data repository interfaces.");

    @ArchTest
    static final ArchRule bean_methods_are_declared_only_in_config_packages =
            methods()
                    .that().areAnnotatedWith(Bean.class)
                    .should().beDeclaredInClassesThat().resideInAPackage("..rest.config..")
                    .because("rest beans must be exposed through explicit configuration classes.");

    @ArchTest
    static final ArchRule configuration_classes_are_declared_only_in_config_packages =
            classes()
                    .that().areAnnotatedWith(AutoConfiguration.class)
                    .or().areAnnotatedWith(Configuration.class)
                    .should().resideInAPackage("..rest.config..")
                    .because("Spring configuration belongs in config packages.");

    @ArchTest
    static final ArchRule configuration_properties_are_declared_only_in_config_packages =
            classes()
                    .that().areAnnotatedWith(ConfigurationProperties.class)
                    .should().resideInAPackage("..rest.config..")
                    .andShould().haveSimpleNameEndingWith("Properties")
                    .andShould().beAnnotatedWith(Validated.class)
                    .because("rest configuration properties must stay validated in config packages.");

    @ArchTest
    static final ArchRule rest_controllers_are_declared_only_in_controller_packages =
            classes()
                    .that().areAnnotatedWith(RestController.class)
                    .should().resideInAPackage("..rest.controller..")
                    .because("HTTP endpoints belong in controller packages.");

    @ArchTest
    static final ArchRule rest_controller_advice_is_declared_only_in_exception_packages =
            classes()
                    .that().areAnnotatedWith(ControllerAdvice.class)
                    .or().areAnnotatedWith(RestControllerAdvice.class)
                    .should().resideInAPackage("..rest.exception..")
                    .because("REST exception handling belongs in the exception package.");

    @ArchTest
    static final ArchRule repositories_are_accessed_only_from_service_config_or_repositories =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "..rest.config..",
                            "..rest.repository..",
                            "..rest.service..")
                    .should().dependOnClassesThat().resideInAPackage("..rest.repository..")
                    .because("repository access must stay behind services and explicit configuration.");

    @ArchTest
    static final ArchRule service_layer_does_not_depend_on_http_dtos_or_handlers =
            noClasses()
                    .that().resideInAPackage("..rest.service..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..rest.dto..",
                            "..rest.handler..")
                    .because("REST services expose internal models and must not depend on HTTP DTOs or handlers.");
}
