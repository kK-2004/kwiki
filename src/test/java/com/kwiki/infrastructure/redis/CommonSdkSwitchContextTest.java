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
 * kk-common 功能开关的消费方契约：web 与异常
 * 自动配置默认开启，Redis 工具集与 Redisson 锁工厂只有在
 * 显式开启后才存在，且被禁用的开关绝不会创建客户端或
 * 触碰默认的 Redis 端点。一切都无需外部中间件即可运行。
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
}
