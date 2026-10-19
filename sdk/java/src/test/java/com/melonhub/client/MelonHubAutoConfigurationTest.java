package com.melonhub.client;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MelonHubAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MelonHubAutoConfiguration.class))
            .withPropertyValues("melonhub.client.api-key=test-only");

    @Test void nonWebApplicationNeedsNeitherMvcNorServlet() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.web", "jakarta.servlet")).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MelonHubClient.class).hasSingleBean(MelonHubAuthorizer.class);
            assertThat(context).doesNotHaveBean("melonHubPermissionInterceptor").doesNotHaveBean("melonHubWebMvcConfigurer");
        });
    }

    @Test void mvcApplicationStillGetsTheInterceptor() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(MelonHubPermissionInterceptor.class)
                .hasSingleBean(MelonHubWebMvcConfigurer.class));
    }

    @Test void absentServletDoesNotLoadTheMvcConfiguration() {
        runner.withClassLoader(new FilteredClassLoader("jakarta.servlet")).run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(MelonHubClient.class).doesNotHaveBean("melonHubPermissionInterceptor"));
    }
}
