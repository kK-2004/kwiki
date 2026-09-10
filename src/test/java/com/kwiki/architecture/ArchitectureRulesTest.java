package com.kwiki.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 模块边界规则：wiki 和 rag 领域包不得依赖基础设施 SDK；仅
 * 基础设施适配器可以接触 Elasticsearch、内容中心、Redis 或 AI 服务商
 * 客户端。内容中心 SDK 另外被限定在其唯一的适配器包内。
 */
class ArchitectureRulesTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(
                                new com.tngtech.archunit.core.importer.ImportOption
                                        .DoNotIncludeTests())
                        .importPackages("com.kwiki");
    }

    @Test
    void wikiDomainDoesNotImportInfrastructureSdks() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..kwiki.wiki..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                "co.elastic.clients..",
                                "com.kk.sdk..",
                                "org.springframework.data.redis..",
                                "org.apache.tika..",
                                "reactor.core..");
        rule.check(classes);
    }

    @Test
    void ragDomainDoesNotImportInfrastructureSdks() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..kwiki.rag..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                "co.elastic.clients..",
                                "com.kk.sdk..",
                                "org.springframework.data.redis..",
                                "org.apache.tika..",
                                "io.netty..",
                                "dev.langchain4j..",
                                "org.bsc.langgraph4j..");
        rule.check(classes);
    }

    @Test
    void noAutomaticAiExecution() {
        noClasses()
                .that()
                .resideInAPackage("..com.kwiki..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "dev.langchain4j.service..",
                        "dev.langchain4j.agentic..",
                        "dev.langchain4j.rag..")
                .check(classes);
    }

    @Test
    void securityPackageStaysMiddlewareFree() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..kwiki.security..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                "co.elastic.clients..",
                                "com.kk.sdk..",
                                "org.springframework.data.redis..");
        rule.check(classes);
    }

    @Test
    void indexingDomainStaysFreeOfContentCenterSdkTypes() {
        // indexing 包拥有自己的 Elasticsearch 端口；只有内容中心
        // SDK 对它而言是外部的。
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..kwiki.indexing..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage("com.kk.sdk..");
        rule.check(classes);
    }

    @Test
    void contentCenterSdkIsConfinedToItsAdapterPackage() {
        // 除内容中心适配器外，所有 kwiki 类都必须保持无 SDK 依赖。
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..com.kwiki..")
                        .and()
                        .resideOutsideOfPackage("com.kwiki.infrastructure.contentcenter..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage("com.kk.sdk..");
        rule.check(classes);
    }

    @Test
    void kkCommonSdkIsConfinedToApprovedPackages() {
        // 共享的 kk-common 工具包仅允许出现在 HTTP 边界
        //（TransDTO/BusinessException）、Redis 适配器内部（RedisUtil），以及
        // Boot 3.5 的 Web 兼容性适配层中；其余部分都必须保持无 SDK 依赖。
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..com.kwiki..")
                        .and()
                        .resideOutsideOfPackage("com.kwiki.infrastructure.redis..")
                        .and()
                        .resideOutsideOfPackage("com.kwiki.infrastructure.config..")
                        .and()
                        .resideOutsideOfPackage("com.kwiki.wiki.api..")
                        .and()
                        .resideOutsideOfPackage("com.kwiki.wiki.archive..")
                        .and()
                        .resideOutsideOfPackage("com.kwiki.security..")
                        .and()
                        .resideOutsideOfPackage("com.kwiki.rag.answer..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage("com.kk2004.common..");
        rule.check(classes);
    }

    @Test
    void redisLayerMustNotOwnASecondJsonStrategy() {
        // Redis 路径上的序列化由 kk-common 的 kkRedisTemplate 负责；
        // 任何 kwiki 的 Redis 类都不得自行接入 Jackson 机制（因此
        // 不安全的默认类型推断不会在此处再次出现）。
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..kwiki.infrastructure.redis..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage("com.fasterxml.jackson.databind..");
        rule.check(classes);
    }

    @Test
    void noKwikiClassMayScanKeysOrEnableDefaultTyping() {
        ArchRule noKeysScan =
                noClasses()
                        .that()
                        .resideInAPackage("..com.kwiki..")
                        .should()
                        .callMethodWhere(
                                new com.tngtech.archunit.base.DescribedPredicate<>(
                                        "org.springframework.data.redis KEYS scan") {
                                    @Override
                                    public boolean test(
                                            com.tngtech.archunit.core.domain.JavaMethodCall call) {
                                        return call.getTarget()
                                                        .getOwner()
                                                        .getName()
                                                        .startsWith(
                                                                "org.springframework.data.redis")
                                                && call.getName().equals("keys");
                                    }
                                })
                        .because(
                                "prefix scans must use SCAN-based deletes"
                                    + " (RedisUtil.deleteByPrefix), never KEYS");
        noKeysScan.check(classes);

        ArchRule noDefaultTyping =
                noClasses()
                        .that()
                        .resideInAPackage("..com.kwiki..")
                        .should()
                        .callMethodWhere(
                                new com.tngtech.archunit.base.DescribedPredicate<>(
                                        "Jackson default typing activation") {
                                    @Override
                                    public boolean test(
                                            com.tngtech.archunit.core.domain.JavaMethodCall call) {
                                        return call.getTarget()
                                                        .getOwner()
                                                        .getName()
                                                        .equals(
                                                                "com.fasterxml.jackson.databind.ObjectMapper")
                                                && (call.getName().startsWith("enableDefaultTyping")
                                                        || call.getName()
                                                                .startsWith(
                                                                        "activateDefaultTyping"));
                                    }
                                })
                        .because(
                                "unsafe polymorphic default typing stays owned by the shared SDK"
                                    + " serializer");
        noDefaultTyping.check(classes);
    }
}
