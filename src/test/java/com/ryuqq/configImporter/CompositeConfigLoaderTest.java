package com.ryuqq.configImporter;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;

import static org.junit.jupiter.api.Assertions.*;

class CompositeConfigLoaderTest {


    @Test
    @DisplayName("classpath 하위 test.yml이 자동 로딩되어야 한다")
    void shouldLoadTestYamlFromClasspath() {
        // given
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("local");

        CompositeConfigLoader loader = new CompositeConfigLoader();

        // when
        loader.postProcessEnvironment(environment, new SpringApplication());

        // then
        assertEquals("imported-from-yml", environment.getProperty("test.key"));
        assertEquals("42", environment.getProperty("test.count")); // YAML은 기본적으로 문자열로 처리됨
    }


    @Test
    @DisplayName("S3에서 가져온 YAML이 prod 환경에서 바인딩되어야 한다")
    void shouldLoadYamlFromS3WhenProdProfile() throws Exception {
        // given
        ConfigurableEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("prod");
        env.getSystemProperties().put("s3.bucket", "mock-bucket");
        env.getSystemProperties().put("s3.key", "mock-key");
        env.getSystemProperties().put("s3.region", "ap-northeast-2");


        String s3Yaml = "external:\n  message: \"hello-from-s3\"";

        InputStream mockInputStream = new java.io.ByteArrayInputStream(s3Yaml.getBytes());

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("s3-config", new InputStreamResource(mockInputStream));
        sources.forEach(ps -> env.getPropertySources().addLast(ps));

        // then
        assertEquals("hello-from-s3", env.getProperty("external.message"));
    }

    @Test
    @DisplayName("S3Client를 주입받아 S3 설정이 환경에 추가되는지 테스트")
    void shouldBindS3YamlPropertiesViaInjectedClient() throws Exception {
        // given
        String s3Yaml = "external:\n  message: \"hello-from-s3\"";
        InputStream stream = new ByteArrayInputStream(s3Yaml.getBytes(StandardCharsets.UTF_8));

        S3Client mockClient = new S3Client() {
            @Override
            public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest getObjectRequest) {
                GetObjectResponse response = GetObjectResponse.builder().build();
                return new ResponseInputStream<>(response, stream);
            }
            @Override public String serviceName() { return "mock-s3"; }
            @Override public void close() {}
        };

        CompositeConfigLoader.S3ClientProvider mockProvider = region -> mockClient;
        CompositeConfigLoader loader = new CompositeConfigLoader(mockProvider);

        ConfigurableEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("prod");
        env.getSystemProperties().put("s3.bucket", "dummy");
        env.getSystemProperties().put("s3.key", "s3-config.yml");
        env.getSystemProperties().put("s3.region", "ap-northeast-2");

        // when
        loader.postProcessEnvironment(env, new SpringApplication());

        // then
        assertEquals("hello-from-s3", env.getProperty("external.message"));
    }


    @Test
    void shouldBindToConfigurationPropertiesClass() throws Exception {
        // given
        String yaml = """
            aws:
              accessKey: "my-access"
              secretKey: "my-secret"
              region: "ap-northeast-2"
            test:
              accessKey: "my-access"
              secretKey: "my-secret"
              region: "ap-northeast-2"
            """;

        ByteArrayResource resource = new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8));
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("s3-config", resource);

        ConfigurableEnvironment env = new StandardEnvironment();
        sources.forEach(ps -> env.getPropertySources().addLast(ps));

        // when
        AwsTestProperties awsProps = Binder.get(env).bind("aws", AwsTestProperties.class).get();

        // then
        assertEquals("my-access", awsProps.getAccessKey());
        assertEquals("my-secret", awsProps.getSecretKey());
        assertEquals("ap-northeast-2", awsProps.getRegion());

        TestProperties testProps = Binder.get(env).bind("test", TestProperties.class).get();

        assertEquals("my-access", testProps.getAccessKey());
        assertEquals("my-secret", testProps.getSecretKey());
        assertEquals("ap-northeast-2", testProps.getRegion());

    }



}