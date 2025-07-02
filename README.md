# Config Importer 📦

`config-importer`는 Spring Boot 애플리케이션에서 `spring.config.import` 없이도 설정 파일을 자동으로 로딩해주는 경량 환경 구성 라이브러리입니다.

## 왜 만들었나?

Spring 프로젝트를 멀티모듈로 구성하다 보면, 각 모듈에서 사용하는 설정 파일들을 `application.yml`에 다음과 같이 직접 `import` 해야 했습니다:

```yaml
spring:
  config:
    import:
      - logging-web.yml
      - monikit-web.yml
      - db-web.yml
      - slack.yml
      - aws.yml
      - monitoring.yml
      - feign.yml
      ....
```

그런데 이게 너무 귀찮았습니다. 특히:

- 모듈 하나 추가할 때마다 `application.yml`에 설정 파일도 수동으로 추가해야 함
- `import` 항목이 길어지고, 한 줄만 빠져도 빌드 장애로 이어질 수 있음
- PR 리뷰나 머지 시 `import` 누락 여부를 항상 신경 써야 함
- Git에 올리면 안 되는 민감 정보(yml 내 API key, DB 비번 등)들을 어떻게 관리할지 애매함

결국 "이걸 왜 내가 매번 신경 써야 하지?" 라는 생각이 들었고,
classpath나 외부 경로에 있으면 알아서 다 읽어오고 바인딩되게 만들도록 했습니다.

복잡한 Vault 시스템이나 KMS 를 따로 도입하지 않아도 되는 점도 장점입니다.

---

## ✨ 주요 기능

| 프로파일 | 동작 방식 |
|----------|------------|
| `prod`   | S3에서 설정 파일 다운로드 후 환경에 자동 등록 |
| `local`, `dev` 등 | classpath 하위의 모든 `.yml` 파일 자동 탐색 및 등록 |

## 🧩 적용 방식
`EnvironmentPostProcessor`를 사용하여 Spring 실행 전에 설정 파일을 환경에 주입합니다.

## 🛠 설치 및 설정

### 1. 의존성 추가 (예: Gradle)
```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.ryuqq:config-importer:1.0.0'
}
```

### 2. spring.factories 등록
`src/main/resources/META-INF/spring.factories`
```properties
org.springframework.boot.env.EnvironmentPostProcessor=\
com.ryuqq.configImporter.CompositeConfigLoader
```

### 3. S3 설정 (prod 환경에서만 동작)
환경변수 또는 시스템 속성으로 설정하거나, `application.yml`에 직접 명시합니다.

#### ✅ application.yml 방식
```yaml
s3:
  bucket: my-config-bucket
  key: config/aws.yml
  region: ap-northeast-2
```

#### ✅ 실행 시 파라미터 방식
```bash
-D s3.bucket=my-config-bucket \
-D s3.key=config/aws.yml \
-D s3.region=ap-northeast-2
```

#### ✅ S3에 올릴 config 예시 (`config/aws.yml`)
```yaml
aws:
  accessKey: ABC123456789
  secretKey: SECRET987654321
  region: ap-northeast-2
```

`prod` 프로파일일 경우 해당 파일이 S3에서 자동으로 다운로드되어 Spring Environment에 등록됩니다.

---
## 🔍 동작 방식

### ✅ prod 프로파일
- AWS S3에서 설정 파일을 다운로드
- `YamlPropertySourceLoader`를 사용해 바인딩
-  S3에 여러 개의 YML이 존재하더라도, 현재 활성화된 Spring Profile (spring.profiles.active)에 맞는 설정(spring.config.activate.on-profile)만 필터링되어 적용

### ✅ local/dev
- classpath의 `application.yml`, `bootstrap.yml` 제외 모든 `.yml` 자동 로딩
- `spring.config.import` 없이 설정 자동 등록
- 이 경우에도, YML 파일 내 spring.config.activate.on-profile 설정을 기준으로, 현재 활성화된 프로파일과 일치하는 섹션만 환경에 반영.

---

## 💬 기타
- 민감한 설정파일을 Git에 포함시키지 않아도 됩니다
- `spring.config.import`를 관리하지 않아도 됩니다
- `application.yml`은 필요한 최소 설정만 유지하세요

---

## 🧪 테스트된 환경
- Java 21
- Spring Boot 3.3+
- AWS SDK v2

---

## 👨‍💻 Author
- Ryusangwon (@ryuqq)