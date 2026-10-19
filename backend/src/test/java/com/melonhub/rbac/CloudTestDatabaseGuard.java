package com.melonhub.rbac;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Runs after Spring config loading, before Flyway or any database connection. */
public class CloudTestDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context) {
        var env = context.getEnvironment();
        String target = env.getProperty("TEST_DB_TARGET", "cloud");
        if (!Set.of("cloud", "local").contains(target)) throw new IllegalStateException("TEST_DB_TARGET must be cloud or local");
        String prefix = target.equals("local") ? "LOCAL_DB_" : "CLOUD_DB_";
        String host = env.getProperty(prefix + "HOST", "");
        if (target.equals("local") && !Set.of("127.0.0.1", "localhost").contains(host)) throw new IllegalStateException("Local tests require a loopback MySQL endpoint");
        validate(env.getProperty("spring.datasource.url", ""), host, env.getProperty(prefix + "PORT", "3306"));
        for (String key : new String[]{"TEST_DB_USERNAME", "TEST_DB_PASSWORD"}) {
            if (env.getProperty(key, "").isBlank()) throw new IllegalStateException(key + " must be configured for integration tests");
        }
    }
    static void validate(String url, String cloudHost, String cloudPort) {
        try {
            if (!url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            URI uri = URI.create(url.substring(5));
            int port = uri.getPort() == -1 ? 3306 : uri.getPort();
            if (cloudHost.isBlank() || !cloudHost.equalsIgnoreCase(uri.getHost()) || port != Integer.parseInt(cloudPort)
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || !"/melonhub_rbac_test".equals(uri.getRawPath())) throw new IllegalArgumentException();
            var options = Arrays.stream(uri.getRawQuery().split("&")).map(option -> option.split("=", 2)).toList();
            Set<String> allowed = Set.of("useUnicode", "characterEncoding", "serverTimezone", "connectTimeout", "socketTimeout", "sslMode");
            if (options.stream().anyMatch(option -> option.length != 2 || !allowed.contains(option[0]))
                    || options.stream().map(option -> option[0]).distinct().count() != options.size()
                    || options.stream().noneMatch(option -> option[0].equals("sslMode") && Set.of("REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY").contains(option[1]))) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Tests require the dedicated melonhub_rbac_test schema on the selected endpoint with TLS; stopped before Flyway");
        }
    }
}
