package com.gialong.relayforge.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Optional;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(
        packages = ModuleBoundaryTests.BASE_PACKAGE,
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ModuleBoundaryTests {

    static final String BASE_PACKAGE = "com.gialong.relayforge";
    private static final String RUNTIME_COMPOSITION_PACKAGE = BASE_PACKAGE + ".runtime..";

    private static final Set<String> BUSINESS_MODULES = Set.of(
            "identity",
            "project",
            "endpoint",
            "delivery"
    );

    @ArchTest
    static final ArchRule BUSINESS_MODULES_FOLLOW_THE_APPROVED_DEPENDENCY_GRAPH = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Identity").definedBy("..identity..")
            .layer("Project").definedBy("..project..")
            .layer("Endpoint").definedBy("..endpoint..")
            .layer("Delivery").definedBy("..delivery..")
            .whereLayer("Identity").mayNotBeAccessedByAnyLayer()
            .whereLayer("Project").mayOnlyBeAccessedByLayers("Endpoint", "Delivery")
            .whereLayer("Endpoint").mayOnlyBeAccessedByLayers("Delivery")
            .whereLayer("Delivery").mayNotBeAccessedByAnyLayer();

    @ArchTest
    static final ArchRule BUSINESS_MODULES_ARE_FREE_OF_CYCLES = slices()
            .matching(BASE_PACKAGE + ".(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule CROSS_MODULE_DEPENDENCIES_TARGET_ONLY_PUBLIC_API = classes()
            .that().resideInAnyPackage(
                    "..identity..",
                    "..project..",
                    "..endpoint..",
                    "..delivery.."
            )
            .should(accessOtherBusinessModulesOnlyThroughApi());

    @ArchTest
    static final ArchRule PUBLIC_API_DEPENDS_ONLY_ON_JAVA_OR_PUBLIC_API = classes()
            .that().resideInAPackage("..api..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..",
                    BASE_PACKAGE + "..api.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule REPOSITORIES_ARE_NOT_PUBLIC_CONTRACTS = noClasses()
            .that().resideInAPackage("..api..")
            .should().haveSimpleNameEndingWith("Repository")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule BUSINESS_MODULES_DO_NOT_DEPEND_ON_RUNTIME_COMPOSITION = noClasses()
            .that().resideInAnyPackage(
                    "..identity..",
                    "..project..",
                    "..endpoint..",
                    "..delivery.."
            )
            .should().dependOnClassesThat().resideInAPackage(RUNTIME_COMPOSITION_PACKAGE);

    @ArchTest
    static final ArchRule RUNTIME_COMPOSITION_TARGETS_ONLY_BUSINESS_PUBLIC_API = classes()
            .that().resideInAPackage(RUNTIME_COMPOSITION_PACKAGE)
            .should(accessBusinessModulesOnlyThroughApi());

    private static ArchCondition<JavaClass> accessOtherBusinessModulesOnlyThroughApi() {
        return new ArchCondition<>("access other business modules only through their api package") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                Optional<String> sourceModule = businessModuleOf(source);
                if (sourceModule.isEmpty()) {
                    return;
                }

                for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    Optional<String> targetModule = businessModuleOf(target);

                    if (targetModule.isEmpty() || sourceModule.equals(targetModule)) {
                        continue;
                    }

                    String publicApiPackage = BASE_PACKAGE + "." + targetModule.get() + ".api";
                    String targetPackage = target.getPackageName();
                    boolean targetsPublicApi = targetPackage.equals(publicApiPackage)
                            || targetPackage.startsWith(publicApiPackage + ".");

                    if (!targetsPublicApi) {
                        String message = dependency.getDescription()
                                + " targets another module outside its public api package";
                        events.add(SimpleConditionEvent.violated(source, message));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> accessBusinessModulesOnlyThroughApi() {
        return new ArchCondition<>("access business modules only through their api package") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    Optional<String> targetModule = businessModuleOf(target);
                    if (targetModule.isEmpty()) {
                        continue;
                    }

                    String publicApiPackage = BASE_PACKAGE + "." + targetModule.get() + ".api";
                    String targetPackage = target.getPackageName();
                    boolean targetsPublicApi = targetPackage.equals(publicApiPackage)
                            || targetPackage.startsWith(publicApiPackage + ".");

                    if (!targetsPublicApi) {
                        String message = dependency.getDescription()
                                + " targets a business module outside its public api package";
                        events.add(SimpleConditionEvent.violated(source, message));
                    }
                }
            }
        };
    }

    private static Optional<String> businessModuleOf(JavaClass javaClass) {
        String prefix = BASE_PACKAGE + ".";
        String packageName = javaClass.getPackageName();
        if (!packageName.startsWith(prefix)) {
            return Optional.empty();
        }

        String relativePackage = packageName.substring(prefix.length());
        int separator = relativePackage.indexOf('.');
        String firstSegment = separator < 0
                ? relativePackage
                : relativePackage.substring(0, separator);

        return BUSINESS_MODULES.contains(firstSegment)
                ? Optional.of(firstSegment)
                : Optional.empty();
    }

}
