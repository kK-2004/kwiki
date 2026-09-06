package com.kwiki.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Module boundary rules: wiki and rag domain packages stay free of infrastructure
 * SDKs; only infrastructure adapters may touch Elasticsearch, the content center,
 * Redis, or AI provider clients. The content-center SDK is additionally confined to
 * its single adapter package.
 */
class ArchitectureRulesTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(new com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests())
                .importPackages("com.kwiki");
    }

    @Test
    void wikiDomainDoesNotImportInfrastructureSdks() {
        ArchRule rule = noClasses().that().resideInAPackage("..kwiki.wiki..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "co.elastic.clients..", "com.kk.sdk..", "org.springframework.data.redis..",
                        "org.apache.tika..", "reactor.core..");
        rule.check(classes);
    }

    @Test
    void ragDomainDoesNotImportInfrastructureSdks() {
        ArchRule rule = noClasses().that().resideInAPackage("..kwiki.rag..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "co.elastic.clients..", "com.kk.sdk..", "org.springframework.data.redis..",
                        "org.apache.tika..", "io.netty..");
        rule.check(classes);
    }

    @Test
    void securityPackageStaysMiddlewareFree() {
        ArchRule rule = noClasses().that().resideInAPackage("..kwiki.security..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "co.elastic.clients..", "com.kk.sdk..", "org.springframework.data.redis..");
        rule.check(classes);
    }

    @Test
    void indexingDomainStaysFreeOfContentCenterSdkTypes() {
        // The indexing package owns its Elasticsearch ports; only the content-center
        // SDK is foreign to it.
        ArchRule rule = noClasses().that().resideInAPackage("..kwiki.indexing..")
                .should().dependOnClassesThat().resideInAnyPackage("com.kk.sdk..");
        rule.check(classes);
    }

    @Test
    void contentCenterSdkIsConfinedToItsAdapterPackage() {
        // Every kwiki class except the content-center adapter must stay SDK-free.
        ArchRule rule = noClasses().that().resideInAPackage("..com.kwiki..")
                .and().resideOutsideOfPackage("com.kwiki.infrastructure.contentcenter..")
                .should().dependOnClassesThat().resideInAnyPackage("com.kk.sdk..");
        rule.check(classes);
    }

    @Test
    void kkCommonSdkIsConfinedToApprovedPackages() {
        // The shared kk-common toolkit is only allowed at the HTTP boundary
        // (TransDTO/BusinessException), inside the Redis adapter (RedisUtil), and in
        // the Boot 3.5 web-compatibility shim; everything else stays SDK-free.
        ArchRule rule = noClasses().that().resideInAPackage("..com.kwiki..")
                .and().resideOutsideOfPackage("com.kwiki.infrastructure.redis..")
                .and().resideOutsideOfPackage("com.kwiki.infrastructure.config..")
                .and().resideOutsideOfPackage("com.kwiki.wiki.api..")
                .and().resideOutsideOfPackage("com.kwiki.security..")
                .and().resideOutsideOfPackage("com.kwiki.rag.answer..")
                .should().dependOnClassesThat().resideInAnyPackage("com.kk2004.common..");
        rule.check(classes);
    }

    @Test
    void redisLayerMustNotOwnASecondJsonStrategy() {
        // Serialization on the Redis path is owned by the kk-common kkRedisTemplate;
        // no kwiki Redis class may wire its own Jackson machinery (and therefore no
        // unsafe default typing can reappear here).
        ArchRule rule = noClasses().that().resideInAPackage("..kwiki.infrastructure.redis..")
                .should().dependOnClassesThat().resideInAnyPackage("com.fasterxml.jackson.databind..");
        rule.check(classes);
    }

    @Test
    void noKwikiClassMayScanKeysOrEnableDefaultTyping() {
        ArchRule noKeysScan = noClasses().that().resideInAPackage("..com.kwiki..")
                .should().callMethodWhere(new com.tngtech.archunit.base.DescribedPredicate<>(
                        "org.springframework.data.redis KEYS scan") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaMethodCall call) {
                        return call.getTarget().getOwner().getName()
                                .startsWith("org.springframework.data.redis")
                                && call.getName().equals("keys");
                    }
                })
                .because("prefix scans must use SCAN-based deletes (RedisUtil.deleteByPrefix), never KEYS");
        noKeysScan.check(classes);

        ArchRule noDefaultTyping = noClasses().that().resideInAPackage("..com.kwiki..")
                .should().callMethodWhere(new com.tngtech.archunit.base.DescribedPredicate<>(
                        "Jackson default typing activation") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaMethodCall call) {
                        return call.getTarget().getOwner().getName()
                                .equals("com.fasterxml.jackson.databind.ObjectMapper")
                                && (call.getName().startsWith("enableDefaultTyping")
                                || call.getName().startsWith("activateDefaultTyping"));
                    }
                })
                .because("unsafe polymorphic default typing stays owned by the shared SDK serializer");
        noDefaultTyping.check(classes);
    }
}
