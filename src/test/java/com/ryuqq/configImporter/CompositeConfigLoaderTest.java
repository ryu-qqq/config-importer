package com.ryuqq.configImporter;

import java.io.ByteArrayInputStream;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

class CompositeConfigLoaderTest {


    @Test
    @DisplayName("classpath 하위 test.yml이 자동 로딩되어야 한다")
    void shouldLoadTestYamlFromClasspath() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("local");

        CompositeConfigLoader loader = new CompositeConfigLoader();
        loader.postProcessEnvironment(environment, new SpringApplication());

        assertEquals("imported-from-yml", environment.getProperty("test.key"));
        assertEquals("42", environment.getProperty("test.count"));
    }

    @Test
    @DisplayName("S3Client를 통해 여러 YAML 파일을 로딩할 수 있어야 한다")
    void shouldLoadMultipleYamlFilesFromS3Directory() throws Exception {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("prod");
        env.getSystemProperties().put("s3.bucket", "mock-bucket");
        env.getSystemProperties().put("s3.keyPrefix", "mock-prefix/");
        env.getSystemProperties().put("s3.region", "ap-northeast-2");

        String awsYaml = "spring:\n  config:\n    activate:\n      on-profile: prod\naws:\n  accessKey: mock-access\n  secretKey: mock-secret";
        String slackYaml = "spring:\n  config:\n    activate:\n      on-profile: prod\nslack:\n  webhook: https://slack.com/api/notify";

        S3Client mockClient = new S3Client() {
            @Override public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
                String key = request.key();
                String yaml = key.contains("aws") ? awsYaml : slackYaml;
                return new ResponseInputStream<>(GetObjectResponse.builder().build(),
                    new ByteArrayInputStream(yaml.replace("\n", "\n").getBytes(StandardCharsets.UTF_8)));
            }

            @Override
            public ListObjectsV2Response listObjectsV2(ListObjectsV2Request request) {
                return ListObjectsV2Response.builder()
                    .contents(S3Object.builder().key("mock-prefix/aws.yml").build(),
                        S3Object.builder().key("mock-prefix/slack.yml").build())
                    .build();
            }

            @Override public String serviceName() { return "mock-s3"; }
            @Override public void close() {}
        };

        CompositeConfigLoader loader = new CompositeConfigLoader(region -> mockClient);
        loader.postProcessEnvironment(env, new SpringApplication());

        assertEquals("mock-access", env.getProperty("aws.accessKey"));
        assertEquals("https://slack.com/api/notify", env.getProperty("slack.webhook"));
    }

    @Test
    void shouldBindToConfigurationPropertiesClass() throws Exception {
        String yaml = """
            spring:
              config:
                activate:
                  on-profile: test

            aws:
              accessKey: "my-access"
              secretKey: "my-secret"
              region: "ap-northeast-2"
            """;

        ByteArrayResource resource = new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8));
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("test-config", resource);

        ConfigurableEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("test");
        sources.forEach(ps -> env.getPropertySources().addLast(ps));

        AwsTestProperties awsProps = Binder.get(env).bind("aws", AwsTestProperties.class).get();

        assertEquals("my-access", awsProps.getAccessKey());
        assertEquals("my-secret", awsProps.getSecretKey());
        assertEquals("ap-northeast-2", awsProps.getRegion());
    }



}