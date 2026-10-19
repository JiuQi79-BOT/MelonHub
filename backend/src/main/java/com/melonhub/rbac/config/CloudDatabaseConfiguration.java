package com.melonhub.rbac.config;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class CloudDatabaseConfiguration {
    @Bean static BeanFactoryPostProcessor databaseTargetGuard(Environment env) {
        return beanFactory -> {
            var profiles = Arrays.asList(env.getActiveProfiles());
            boolean test = profiles.contains("test");
            String database = test ? "melonhub_rbac_test" : "melonhub_rbac";
            try {
                String target = test ? env.getProperty("TEST_DB_TARGET", "cloud") : profiles.contains("local") ? "local" : "cloud";
                if (!Set.of("cloud", "local").contains(target)
                        || profiles.contains("cloud") && profiles.contains("local")
                        || !test && !profiles.contains(target)) throw new IllegalArgumentException();
                String prefix = target.equals("local") ? "LOCAL_DB_" : "CLOUD_DB_";
                String host = env.getRequiredProperty(prefix + "HOST");
                if (target.equals("local") && !Set.of("127.0.0.1", "localhost").contains(host)) throw new IllegalArgumentException();
                String url = env.getRequiredProperty("spring.datasource.url");
                if (!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
                URI uri = URI.create(url.substring(5));
                if (!host.equalsIgnoreCase(uri.getHost())
                        || (uri.getPort() == -1 ? 3306 : uri.getPort()) != Integer.parseInt(env.getProperty(prefix + "PORT", "3306"))
                        || !uri.getRawPath().equals("/" + database) || uri.getRawUserInfo() != null || uri.getRawFragment() != null) throw new IllegalArgumentException();
                var options = Arrays.stream(uri.getRawQuery().split("&")).map(option -> option.split("=", 2)).toList();
                Set<String> allowed = Set.of("useUnicode", "characterEncoding", "serverTimezone", "connectTimeout", "socketTimeout", "sslMode");
                if (options.stream().anyMatch(option -> option.length != 2 || !allowed.contains(option[0]))
                        || options.stream().map(option -> option[0]).distinct().count() != options.size()
                        || options.stream().noneMatch(option -> option[0].equals("sslMode") && Set.of("REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY").contains(option[1]))) throw new IllegalArgumentException();
                if (env.getRequiredProperty("spring.datasource.username").isBlank()
                        || env.getRequiredProperty("spring.datasource.password").isBlank()) throw new IllegalArgumentException();
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("Configure the selected cloud/local MySQL endpoint, credentials, TLS and the dedicated " + database + " schema in .env; startup stopped before Flyway");
            }
        };
    }
}
