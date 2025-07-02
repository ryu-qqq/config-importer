package com.ryuqq.configImporter;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * CompositeConfigLoader는 Spring Boot 애플리케이션 실행 시
 * {@link EnvironmentPostProcessor}로 동작하여 다음과 같은 설정 로딩 기능을 제공한다:
 * 1. prod 환경일 경우:
 *    - S3에서 config 파일을 다운로드하고 {@link PropertySource}로 등록한다.
 *
 * 2. 그 외 환경(local, dev 등):
 *    - classpath 전체를 스캔하여 application.yml, bootstrap.yml을 제외한
 *      모든 yml 파일을 자동으로 로딩한다.
 *
 * 해당 클래스는 application.yml의 spring.config.import 없이도 설정 파일을 자동 바인딩할 수 있도록 도와준다.
 */

public class CompositeConfigLoader implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(CompositeConfigLoader.class);

    private final S3ClientProvider s3ClientProvider;

    public CompositeConfigLoader() {
        this(new DefaultS3ClientProvider());
    }

    public CompositeConfigLoader(S3ClientProvider s3ClientProvider) {
        this.s3ClientProvider = s3ClientProvider;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        log.info("ConfigLoader Loading config...");
        loadLocalYmlConfigsIfNotProd(environment);
        loadS3ConfigsIfProd(environment);
    }

    /**
     * prod 프로파일이 아닐 경우, classpath 하위의 모든 yml 파일을 로딩하여 환경에 추가한다.
     */
    private void loadLocalYmlConfigsIfNotProd(ConfigurableEnvironment environment) {
        if (environment.getActiveProfiles().length > 0 && isProdProfile(environment)) return;

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:**/*.yml");
            YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

            for (Resource resource : resources) {
                String name = resource.getFilename();
                if (name == null || name.startsWith("application") || name.equals("bootstrap.yml")) continue;

                List<PropertySource<?>> sources = loader.load(name, resource);
                for (PropertySource<?> ps : sources) {
                    if (shouldInclude(ps, environment)) {
                        environment.getPropertySources().addLast(ps);
                        log.info("[ConfigImporter] Loaded local yml config: {}", ps.getName());
                    } else {
                        log.info("[ConfigImporter] Skipped local yml config: {} (profile mismatch)", ps.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ConfigImporter] Failed to load local yml configs: {}", e.getMessage());
        }
    }

    private void loadS3ConfigsIfProd(ConfigurableEnvironment environment) {
        try {
            if (!isProdProfile(environment)) {
                log.info("[ConfigImporter] Skipping S3 config (not prod profile)");
                return;
            }

            String bucket = environment.getProperty("s3.bucket", "my-secure-bucket");
            String prefix = environment.getProperty("s3.keyPrefix", "config/");
            String region = environment.getProperty("s3.region", "ap-northeast-2");

            try (S3Client s3 = s3ClientProvider.get(region)) {
                ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build();

                ListObjectsV2Response listRes = s3.listObjectsV2(listReq);
                List<S3Object> ymlObjects = listRes.contents().stream()
                    .filter(obj -> obj.key().endsWith(".yml"))
                    .toList();

                if (ymlObjects.isEmpty()) {
                    log.warn("[ConfigImporter] No yml files found in s3://{}/{}", bucket, prefix);
                    return;
                }

                YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
                for (S3Object obj : ymlObjects) {
                    String key = obj.key();
                    log.info("[ConfigImporter] Loading config from s3://{}/{}", bucket, key);

                    GetObjectRequest getReq = GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build();

                    try (InputStream input = s3.getObject(getReq)) {
                        InputStreamResource res = new InputStreamResource(input);
                        List<PropertySource<?>> sources = loader.load("s3-" + key, res);
                        for (PropertySource<?> ps : sources) {
                            if (shouldInclude(ps, environment)) {
                                environment.getPropertySources().addLast(ps);
                                log.info("[ConfigImporter] Loaded s3 yml config: {}", ps.getName());
                            } else {
                                log.info("[ConfigImporter] Skipped s3 yml config: {} (profile mismatch)", ps.getName());
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[ConfigImporter] Failed to load {}: {}", key, e.getMessage());
                    }
                }

            } catch (Exception e) {
                log.error("[ConfigImporter] S3 config load failed: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.warn("ConfigLoader S3 config load failed: {}", e.getMessage());
        }
    }

    private boolean shouldInclude(PropertySource<?> ps, ConfigurableEnvironment env) {
        Object profileKey = ps.getProperty("spring.config.activate.on-profile");
        if (profileKey == null) return true;
        return Arrays.stream(env.getActiveProfiles())
            .anyMatch(p -> p.equalsIgnoreCase(profileKey.toString()));
    }

    private boolean isProdProfile(ConfigurableEnvironment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if (profile.contains("prod")) {
                return true;
            }
        }
        return false;
    }

    public interface S3ClientProvider {
        S3Client get(String region);
    }

    public static class DefaultS3ClientProvider implements S3ClientProvider {
        @Override
        public S3Client get(String region) {
            return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        }
    }

}
