package com.ryuqq.configImporter;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class S3TestClient {

    public static S3Client createMock(String ymlContent) {
        return new S3Client() {
            @Override
            public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest getObjectRequest) {
                InputStream stream = new ByteArrayInputStream(ymlContent.getBytes(StandardCharsets.UTF_8));
                return new ResponseInputStream<>(GetObjectResponse.builder().build(), stream);
            }

            @Override
            public String serviceName() {
                return "mock-s3";
            }

            @Override public void close() {}
        };
    }

}
