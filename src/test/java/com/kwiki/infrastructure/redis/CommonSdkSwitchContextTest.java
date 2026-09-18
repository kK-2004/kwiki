package com.kwiki.infrastructure.redis;

import com.kk2004.common.autoconfigure.CommonBoot3AutoConfiguration;
import com.kk2004.common.autoconfigure.RedisToolkitAutoConfiguration;
import com.kk2004.common.autoconfigure.RedissonLockAutoConfiguration;
import com.kk2004.common.lock.DistributedLockFactory;
import com.kk2004.common.redis.RedisUtil;
import com.kwiki.testutil.StandardTestProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * kk-common 功能开关的消费方契约：kwiki 生产默认（application.yml）把
 * Redis 工具集与 Redisson 锁工厂开启并允许环境变量显式覆盖；SDK 自身的
 * 条件在开关关闭时绝不创建客户端、不触碰默认端点；共享测试夹具显式
 * 关闭两个开关保证离线。一切都无需外部中间件即可运行。
 */
class CommonSdkSwitchContextTest {

    private final WebApplicationContextRunner servletRunner = new WebApplicationContextRunner(
            AnnotationConfigServletWebApplicationContext::new)
            .withConfiguration(AutoConfigurations.of(
                    CommonBoot3AutoConfiguration.class,
                    RedisToolkitAutoConfiguration.class,
                    RedissonLockAutoConfiguration.class));

    /** Redis 工具上下文：SDK 自动配置加一个（永不连接的）假工厂。 */
    private final ApplicationContextRunner redisRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisToolkitAutoConfiguration.class))
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class));

    @Test
    void omittedSwitchesKeepWebAndExceptionOnAndRedisOff() {
        servletRunner.run(context -> {
            assertThat(context).hasSingleBean(com.kk2004.common.exception.GlobalExceptionHandler.class);
            assertThat(context).hasSingleBean(com.kk2004.common.web.RequestContextFilter.class);
            assertThat(context).hasSingleBean(com.kk2004.common.web.RequestContextTaskDecorator.class);
            assertThat(context).doesNotHaveBean(RedisUtil.class);
            assertThat(context).doesNotHaveBean(RedissonClient.class);
            assertThat(context).doesNotHaveBean(DistributedLockFactory.class);
        });
    }

    @Test
    void explicitFalseIsRespectedForEverySwitch() {
        servletRunner.withPropertyValues(
                        "kk.common.web.enabled=false",
                        "kk.common.exception.enabled=false",
                        "kk.common.redis.enabled=false",
                        "kk.common.redisson.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            com.kk2004.common.exception.GlobalExceptionHandler.class);
                    assertThat(context).doesNotHaveBean(
                            com.kk2004.common.web.RequestContextFilter.class);
                    assertThat(context).doesNotHaveBean(RedisUtil.class);
                    assertThat(context).doesNotHaveBean(DistributedLockFactory.class);
                });
    }

    @Test
    void explicitlyDisabledRedissonNeverTouchesTheDefaultEndpoint() {
        // 应用在此处仍会把 spring.data.redis.* 解析到 localhost:6379；该用例
        // 之所以能通过，仅仅是因为开关关闭时不允许任何东西创建客户端。
        servletRunner.withPropertyValues(
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=6379",
                        "kk.common.redis.enabled=false",
                        "kk.common.redisson.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RedissonClient.class);
                    assertThat(context).doesNotHaveBean(DistributedLockFactory.class);
                    assertThat(context).doesNotHaveBean(RedisUtil.class);
                    assertThat(context.getStartupFailure()).isNull();
                });
    }

    @Test
    void redisAloneEnablesTheToolkitButNeverRedisson() {
        redisRunner.withPropertyValues("kk.common.redis.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(RedisUtil.class);
                    assertThat(context).doesNotHaveBean(RedissonClient.class);
                    assertThat(context).doesNotHaveBean(DistributedLockFactory.class);
                });
    }

    @Test
    void redisToolkitStaysOffWithoutAConnectionFactoryEvenWhenEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisToolkitAutoConfiguration.class))
                .withPropertyValues("kk.common.redis.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RedisUtil.class);
                    assertThat(context.getStartupFailure()).isNull();
                });
    }

    @Test
    void enabledRedissonNeedsARedissonClientAndThenBuildsTheLockFactory() {
        // 真实的 RedissonClient 会向外拨号；提供一个假实例让上下文保持离线，
        // 同时证明开关打开后该工厂确实由它派生而来。
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedissonLockAutoConfiguration.class))
                .withPropertyValues("kk.common.redisson.enabled=true")
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(DistributedLockFactory.class);
                    assertThat(context.getBean(DistributedLockFactory.class))
                            .isInstanceOf(com.kk2004.common.lock.RedissonDistributedLockFactory.class);
                });
    }

    @Test
    void sdkAutoConfigurationsStayOffOutsideServletWebApps() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonBoot3AutoConfiguration.class))
                .run(context -> assertThat(context)
                        .doesNotHaveBean(com.kk2004.common.exception.GlobalExceptionHandler.class));
    }

    @Test
    void standardTestFixturesExcludeSpringRedisAutoConfiguration() {
        assertThat(StandardTestProperties.REDIS_AUTO_CONFIGURATIONS_EXCLUDED)
                .contains(RedisAutoConfiguration.class.getName(),
                        RedisRepositoriesAutoConfiguration.class.getName());
    }

    @Test
    void standardTestFixturesExplicitlyDisableRedisAndRedisson() {
        // 生产默认已是 true；离线测试上下文必须显式关闭两个开关，
        // 否则 kkRedisTemplate/RedissonClient 的装配会读取 spring.data.redis.*。
        assertThat(StandardTestProperties.VALUES).contains(
                "kk.common.redis.enabled=false",
                "kk.common.redisson.enabled=false");
    }

    @Test
    void productionYamlDefaultsEnableRedisAndRedissonAndKeepOverrides() throws java.io.IOException {
        // 直接解析 application.yml 中的 kk.common 段：缺省环境变量时两个
        // 开关解析为 true；显式提供 KK_COMMON_* 时以覆盖值为准。
        org.springframework.core.io.Resource resource =
                new org.springframework.core.io.ClassPathResource("application.yml");
        org.springframework.boot.env.YamlPropertySourceLoader loader =
                new org.springframework.boot.env.YamlPropertySourceLoader();
        org.springframework.core.env.MutablePropertySources yaml = new org.springframework.core.env.MutablePropertySources();
        loader.load("application.yml", resource).forEach(yaml::addLast);

        org.springframework.core.env.MutablePropertySources withOverride = new org.springframework.core.env.MutablePropertySources();
        withOverride.addFirst(new org.springframework.core.env.MapPropertySource(
                "env-override", java.util.Map.of("KK_COMMON_REDIS_ENABLED", "false")));
        loader.load("application.yml", resource).forEach(withOverride::addLast);

        org.springframework.boot.context.properties.bind.Binder defaults = binderFor(yaml);
        org.springframework.boot.context.properties.bind.Binder overridden = binderFor(withOverride);

        assertThat(defaults.bind("kk.common.redis.enabled", Boolean.class).get()).isTrue();
        assertThat(defaults.bind("kk.common.redisson.enabled", Boolean.class).get()).isTrue();
        assertThat(defaults.bind("kk.common.web.enabled", Boolean.class).get()).isTrue();
        assertThat(defaults.bind("kk.common.exception.enabled", Boolean.class).get()).isTrue();
        assertThat(overridden.bind("kk.common.redis.enabled", Boolean.class).get()).isFalse();
        assertThat(overridden.bind("kk.common.redisson.enabled", Boolean.class).get()).isTrue();
    }

    @Test
    void productionDefaultsWireToolkitAndLockFactoryFromFakeClients() {
        // 生产默认组合：两个开关全开 + 假连接工厂/假 Redisson 客户端，
        // 断言 SDK 在开启状态下的完整 bean 装配，且全程无外拨。
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RedisToolkitAutoConfiguration.class, RedissonLockAutoConfiguration.class))
                .withPropertyValues(
                        "kk.common.redis.enabled=true",
                        "kk.common.redisson.enabled=true")
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(RedisUtil.class);
                    assertThat(context).hasSingleBean(
                            org.springframework.data.redis.core.RedisTemplate.class);
                    assertThat(context).hasSingleBean(DistributedLockFactory.class);
                    assertThat(context).hasSingleBean(RedissonClient.class);
                    assertThat(context.getStartupFailure()).isNull();
                });
    }

    @Test
    void enabledApplicationRedisConfigurationDoesNotStartRepositoryScanningEarly() {
        // 回归：RedisRepositoriesAutoConfiguration 曾被普通 @Configuration
        // 直接导入，在正式默认开关为 true 时早于 Boot 基础包注册执行，导致
        // "Unable to retrieve @EnableAutoConfiguration base packages"。
        new ApplicationContextRunner()
                .withUserConfiguration(KwikiRedisConfiguration.class)
                .withPropertyValues(
                        "kk.common.redis.enabled=true",
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=6379")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context).hasSingleBean(RedisConnectionFactory.class);
                    assertThat(context).hasSingleBean(org.springframework.boot.actuate.health.HealthIndicator.class);
                });
    }

    private static org.springframework.boot.context.properties.bind.Binder binderFor(
            org.springframework.core.env.PropertySources sources) {
        return new org.springframework.boot.context.properties.bind.Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources),
                new org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(sources));
    }
}
