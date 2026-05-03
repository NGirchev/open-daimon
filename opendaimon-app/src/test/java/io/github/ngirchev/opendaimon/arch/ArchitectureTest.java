package io.github.ngirchev.opendaimon.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;

import java.util.Set;
import java.util.TreeSet;

/**
 * Executable architectural invariants for the {@code opendaimon-*} library modules.
 *
 * <p>Library modules are published to Maven Central and consumed independently, so this
 * test codifies the module and bean-wiring boundaries from AGENTS.md.
 */
@AnalyzeClasses(
        packages = "io.github.ngirchev.opendaimon",
        importOptions = {
                ImportOption.DoNotIncludeTests.class,
                ArchitectureTest.IncludeOpendaimonOnly.class
        }
)
class ArchitectureTest {

    /**
     * Admits exploded class files unconditionally and only those JAR entries
     * whose URI contains "/opendaimon-" (our own multi-module JARs).
     */
    public static class IncludeOpendaimonOnly implements ImportOption {
        @Override
        public boolean includes(Location location) {
            if (!location.contains(".jar")) {
                return true;
            }
            return location.contains("/opendaimon-");
        }
    }

    private static final SliceAssignment LIBRARY_MODULES = new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(JavaClass cls) {
            String pkg = cls.getPackageName();
            if (pkg.startsWith("io.github.ngirchev.opendaimon.common")) {
                return SliceIdentifier.of("common");
            }
            if (pkg.startsWith("io.github.ngirchev.opendaimon.telegram")) {
                return SliceIdentifier.of("telegram");
            }
            if (pkg.startsWith("io.github.ngirchev.opendaimon.rest")) {
                return SliceIdentifier.of("rest");
            }
            if (pkg.startsWith("io.github.ngirchev.opendaimon.ai.springai")) {
                return SliceIdentifier.of("spring-ai");
            }
            if (pkg.startsWith("io.github.ngirchev.opendaimon.ai.ui")) {
                return SliceIdentifier.of("ui");
            }
            return SliceIdentifier.ignore();
        }

        @Override
        public String getDescription() {
            return "published library modules";
        }
    };

    private static final ArchCondition<JavaClass> DEPEND_ON_AT_MOST_ONE_DELIVERY_CHANNEL =
            new ArchCondition<>("depend on at most one delivery channel module") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    if (item.getPackageName().equals("io.github.ngirchev.opendaimon")) {
                        return;
                    }
                    Set<String> deliveryChannels = new TreeSet<>();
                    for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                        String packageName = dependency.getTargetClass().getPackageName();
                        if (packageName.startsWith("io.github.ngirchev.opendaimon.telegram")) {
                            deliveryChannels.add("telegram");
                        }
                        if (packageName.startsWith("io.github.ngirchev.opendaimon.rest")) {
                            deliveryChannels.add("rest");
                        }
                    }
                    if (deliveryChannels.size() > 1) {
                        events.add(SimpleConditionEvent.violated(
                                item,
                                item.getName() + " depends on multiple delivery channels: " + deliveryChannels));
                    }
                }
            };

    @ArchTest
    static final ArchRule library_modules_use_no_service_or_component_stereotypes =
            noClasses()
                    .that().resideInAnyPackage(
                            "io.github.ngirchev.opendaimon.common..",
                            "io.github.ngirchev.opendaimon.ai.springai..",
                            "io.github.ngirchev.opendaimon.telegram..",
                            "io.github.ngirchev.opendaimon.rest..",
                            "io.github.ngirchev.opendaimon.ai.ui..")
                    .should().beAnnotatedWith(org.springframework.stereotype.Service.class)
                    .orShould().beAnnotatedWith(org.springframework.stereotype.Component.class)
                    .because("Library modules export beans via @Bean methods in @Configuration classes.");

    @ArchTest
    static final ArchRule library_modules_use_no_repository_classes =
            noClasses()
                    .that().resideInAnyPackage(
                            "io.github.ngirchev.opendaimon.common..",
                            "io.github.ngirchev.opendaimon.ai.springai..",
                            "io.github.ngirchev.opendaimon.telegram..",
                            "io.github.ngirchev.opendaimon.rest..",
                            "io.github.ngirchev.opendaimon.ai.ui..")
                    .and().areNotInterfaces()
                    .should().beAnnotatedWith(org.springframework.stereotype.Repository.class)
                    .because("@Repository is only allowed on Spring Data repository interfaces.");

    @ArchTest
    static final ArchRule library_modules_have_no_cyclic_dependencies =
            slices().assignedFrom(LIBRARY_MODULES)
                    .should().beFreeOfCycles()
                    .because("Cycles between published library modules break independent consumption.");

    @ArchTest
    static final ArchRule telegram_module_does_not_depend_on_rest_module =
            noClasses()
                    .that().resideInAPackage("io.github.ngirchev.opendaimon.telegram..")
                    .should().dependOnClassesThat().resideInAPackage("io.github.ngirchev.opendaimon.rest..")
                    .because("Delivery channels must stay independently consumable.");

    @ArchTest
    static final ArchRule rest_module_does_not_depend_on_telegram_module =
            noClasses()
                    .that().resideInAPackage("io.github.ngirchev.opendaimon.rest..")
                    .should().dependOnClassesThat().resideInAPackage("io.github.ngirchev.opendaimon.telegram..")
                    .because("Delivery channels must stay independently consumable.");

    @ArchTest
    static final ArchRule only_app_depends_on_multiple_delivery_channel_modules =
            classes()
                    .that().resideInAPackage("io.github.ngirchev.opendaimon..")
                    .should(DEPEND_ON_AT_MOST_ONE_DELIVERY_CHANNEL)
                    .because("Only the runtime app may compose multiple delivery channels.");

    @ArchTest
    static final ArchRule repositories_are_accessed_only_from_service_or_config =
            layeredArchitecture().consideringAllDependencies()
                    .layer("Repository").definedBy("io.github.ngirchev.opendaimon..repository..")
                    .layer("Service").definedBy("io.github.ngirchev.opendaimon..service..")
                    .layer("Config").definedBy("io.github.ngirchev.opendaimon..config..")
                    .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service", "Config")
                    .because("Repository access must stay behind service APIs and explicit @Bean configuration.");
}
