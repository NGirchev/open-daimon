package io.github.ngirchev.opendaimon.rest.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.Set;

@AnalyzeClasses(
        packages = "io.github.ngirchev.opendaimon.rest",
        importOptions = {
                ImportOption.DoNotIncludeTests.class,
                ImportOption.DoNotIncludeJars.class
        }
)
class RestArchitectureTest {

    private static final String REST_PACKAGE = "io.github.ngirchev.opendaimon.rest";
    private static final String REST_MODULE_PACKAGE = REST_PACKAGE + "..";
    private static final String REST_CONFIG_PACKAGE = REST_PACKAGE + ".config..";
    private static final String REST_CONTROLLER_PACKAGE = REST_PACKAGE + ".controller..";
    private static final String REST_DTO_PACKAGE = REST_PACKAGE + ".dto..";
    private static final String REST_HANDLER_PACKAGE = REST_PACKAGE + ".handler..";
    private static final String REST_MODEL_PACKAGE = REST_PACKAGE + ".model..";
    private static final String REST_REPOSITORY_PACKAGE = REST_PACKAGE + ".repository..";
    private static final String REST_SERVICE_PACKAGE = REST_PACKAGE + ".service..";

    private static final String REST_CONTROLLER_PACKAGE_PREFIX = REST_PACKAGE + ".controller";
    private static final String REST_HANDLER_PACKAGE_PREFIX = REST_PACKAGE + ".handler";

    private static final String CHAT_SERVICE = REST_PACKAGE + ".service.ChatService";
    private static final Set<String> CHAT_SERVICE_COMMAND_BRIDGE_TYPES = Set.of(
            REST_PACKAGE + ".handler.RestChatCommand",
            REST_PACKAGE + ".handler.RestChatCommandType"
    );

    private static final ArchCondition<JavaClass> HAVE_REST_CONFIGURATION_PREFIX =
            new ArchCondition<>("have an open-daimon.rest configuration prefix") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    ConfigurationProperties annotation = item.getAnnotationOfType(ConfigurationProperties.class);
                    String prefix = annotation.prefix().isBlank() ? annotation.value() : annotation.prefix();
                    if (!prefix.startsWith("open-daimon.rest")) {
                        events.add(SimpleConditionEvent.violated(
                                item,
                                item.getName() + " uses configuration prefix '" + prefix + "'"));
                    }
                }
            };

    private static final ArchCondition<JavaClass> HAVE_NO_REST_DELIVERY_DEPENDENCIES_EXCEPT_COMMAND_BRIDGE =
            new ArchCondition<>("not depend on REST delivery classes except the ChatService command bridge") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    item.getDirectDependenciesFromSelf().stream()
                            .map(Dependency::getTargetClass)
                            .filter(RestArchitectureTest::isRestDeliveryClass)
                            .filter(targetClass -> !isAllowedCommandBridge(item, targetClass))
                            .forEach(targetClass -> events.add(SimpleConditionEvent.violated(
                                    item,
                                    item.getName() + " depends on delivery class " + targetClass.getName())));
                }
            };

    @ArchTest
    static final ArchRule rest_module_uses_no_service_or_component_stereotypes =
            noClasses()
                    .that().resideInAPackage(REST_MODULE_PACKAGE)
                    .should().beAnnotatedWith(Service.class)
                    .orShould().beAnnotatedWith(Component.class)
                    .because("REST starter beans must be exposed through explicit auto-configuration.");

    @ArchTest
    static final ArchRule rest_module_uses_no_repository_classes =
            noClasses()
                    .that().resideInAPackage(REST_MODULE_PACKAGE)
                    .and().areNotInterfaces()
                    .should().beAnnotatedWith(Repository.class)
                    .because("@Repository is only allowed on Spring Data repository interfaces.");

    @ArchTest
    static final ArchRule bean_methods_are_declared_only_in_config_package =
            methods()
                    .that().areAnnotatedWith(Bean.class)
                    .should().beDeclaredInClassesThat().resideInAPackage(REST_CONFIG_PACKAGE)
                    .because("REST beans must be exposed through explicit configuration classes.");

    @ArchTest
    static final ArchRule configuration_classes_are_declared_only_in_config_package =
            classes()
                    .that().areAnnotatedWith(AutoConfiguration.class)
                    .or().areAnnotatedWith(Configuration.class)
                    .should().resideInAPackage(REST_CONFIG_PACKAGE)
                    .because("REST Spring configuration belongs in the config package.");

    @ArchTest
    static final ArchRule configuration_properties_follow_rest_conventions =
            classes()
                    .that().areAnnotatedWith(ConfigurationProperties.class)
                    .should().resideInAPackage(REST_CONFIG_PACKAGE)
                    .andShould().haveSimpleNameEndingWith("Properties")
                    .andShould().beAnnotatedWith(Validated.class)
                    .andShould(HAVE_REST_CONFIGURATION_PREFIX)
                    .because("REST configuration properties must stay validated and under open-daimon.rest.");

    @ArchTest
    static final ArchRule repositories_are_interfaces =
            classes()
                    .that().resideInAPackage(REST_REPOSITORY_PACKAGE)
                    .and().haveSimpleNameEndingWith("Repository")
                    .should().beInterfaces()
                    .because("REST repositories are Spring Data interfaces, not concrete infrastructure classes.");

    @ArchTest
    static final ArchRule repositories_are_accessed_only_from_service_config_or_repositories =
            noClasses()
                    .that().resideInAPackage(REST_MODULE_PACKAGE)
                    .and().resideOutsideOfPackages(
                            REST_CONFIG_PACKAGE,
                            REST_REPOSITORY_PACKAGE,
                            REST_SERVICE_PACKAGE)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.github.ngirchev.opendaimon.common.repository..",
                            REST_REPOSITORY_PACKAGE)
                    .because("repository access must stay behind services and explicit auto-configuration.");

    @ArchTest
    static final ArchRule controllers_do_not_depend_on_repositories_or_handlers =
            noClasses()
                    .that().resideInAPackage(REST_CONTROLLER_PACKAGE)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            REST_HANDLER_PACKAGE,
                            REST_REPOSITORY_PACKAGE)
                    .because("REST controllers should delegate to services instead of handlers or repositories.");

    @ArchTest
    static final ArchRule dto_and_model_are_passive =
            noClasses()
                    .that().resideInAnyPackage(
                            REST_DTO_PACKAGE,
                            REST_MODEL_PACKAGE)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            REST_CONFIG_PACKAGE,
                            REST_CONTROLLER_PACKAGE,
                            REST_HANDLER_PACKAGE,
                            REST_REPOSITORY_PACKAGE,
                            REST_SERVICE_PACKAGE)
                    .because("REST DTO and model classes must not know about runtime layers.");

    @ArchTest
    static final ArchRule services_do_not_depend_on_delivery_layers_except_command_bridge =
            classes()
                    .that().resideInAPackage(REST_SERVICE_PACKAGE)
                    .should(HAVE_NO_REST_DELIVERY_DEPENDENCIES_EXCEPT_COMMAND_BRIDGE)
                    .because("REST services should stay behind delivery layers; ChatService keeps the current public command bridge.");

    private static boolean isRestDeliveryClass(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        return packageName.startsWith(REST_CONTROLLER_PACKAGE_PREFIX)
                || packageName.startsWith(REST_HANDLER_PACKAGE_PREFIX);
    }

    private static boolean isAllowedCommandBridge(JavaClass item, JavaClass targetClass) {
        return item.getName().equals(CHAT_SERVICE)
                && CHAT_SERVICE_COMMAND_BRIDGE_TYPES.contains(targetClass.getName());
    }
}
