package com.gateway;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule no_core_http_client_request =
        noClasses()
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("io.vertx.core.http.HttpClientRequest")
            .allowEmptyShould(true)
            .because("Use Mutiny HttpClient API to keep reactive chains on the event loop — " +
                     "core API causes race conditions with @RunOnVirtualThread (see pipeTo bug)");

    @ArchTest
    static final ArchRule no_core_http_client_response =
        noClasses()
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("io.vertx.core.http.HttpClientResponse")
            .allowEmptyShould(true)
            .because("Use Mutiny HttpClient API to keep reactive chains on the event loop — " +
                     "core API causes race conditions with @RunOnVirtualThread (see pipeTo bug)");

    @ArchTest
    static final ArchRule no_getDelegate_escape =
        noClasses()
            .should().callMethodWhere(
                com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                    com.tngtech.archunit.base.DescribedPredicate.describe(
                        "is getDelegate()",
                        method -> method.getName().equals("getDelegate")
                                  && method.getOwner().getPackageName().startsWith("io.vertx.mutiny")
                    )
                )
            )
            .allowEmptyShould(true)
            .because("getDelegate() escapes from Mutiny to core Vert.x, " +
                     "bypassing reactive chain safety on virtual threads");
}
