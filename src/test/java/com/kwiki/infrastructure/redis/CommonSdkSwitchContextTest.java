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
 * Consumer-side contract of the kk-common feature switches: web and exception
 * auto-configurations default on, Redis toolkit and the Redisson lock factory only
 * exist after an explicit opt-in, and a disabled switch can never create a client or
 * touch the default Redis endpoint. Everything runs without external middleware.
 */
class CommonSdkSwitchContextTest {

    private final WebApplicationContextRunner servletRunner = new WebApplicationContextRunner(
            AnnotationConfigServletWebApplicationContext::new)
            .withConfiguration(AutoConfigurations.of(
                    CommonBoot3AutoConfiguration.class,
                    RedisToolkitAutoConfiguration.class,
                    RedissonLockAutoConfiguration.class));

    /** Redis tooling context: SDK auto-configs plus a (never-connecting) fake factory. */
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
        // The app still resolves spring.data.redis.* to localhost:6379 here; the run
        // only succeeds because nothing may create a client while the switch is off.
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
        // A real RedissonClient would dial out; providing one keeps the context offline
        // while proving the factory is derived from it once the switch is on.
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
