# SubAuth AI 설치 진입점

이 문서는 AI 코딩 도구가 기존 Spring AI 프로젝트에 SubAuth를 안전하게 적용하기
위한 작업 지침입니다. 사용자는 아래 문장을 AI에 전달하면 됩니다.

```text
현재 열려 있는 Spring AI 프로젝트에 SubAuth를 적용해 줘.
먼저 https://raw.githubusercontent.com/kchanis1223/SubAuth/main/AI_SETUP.md 를 읽고
그 절차를 따라 줘. 기존 코드와 Spring Boot/Spring AI 버전은 유지하고, 계정 로그인이나
인증 정보 설정처럼 내가 직접 해야 하는 단계에서는 명령을 알려 주고 기다려 줘.
```

AI가 외부 문서를 읽을 수 없다면 SubAuth 저장소를 받은 뒤 이 파일의 로컬 경로를
알려 줍니다.

```bash
git clone https://github.com/kchanis1223/SubAuth.git
```

## AI 작업 원칙

이 문서를 읽은 AI는 다음 원칙을 지켜야 합니다.

1. 먼저 현재 프로젝트를 조사하고 Gradle과 Maven 중 하나를 선택합니다.
2. 기존 Spring Boot와 Spring AI 버전, BOM, `ChatClient` 코드를 유지합니다.
3. Gradle 프로젝트에 `pom.xml`을 만들거나 Maven 설정을 추가하지 않습니다.
4. Maven 프로젝트에 `build.gradle`을 만들거나 Gradle 설정을 추가하지 않습니다.
5. 토큰, OAuth 정보, `~/.codex/auth.json` 내용을 읽거나 출력하지 않습니다.
6. 인증 정보는 프로젝트 파일이나 Git에 추가하지 않습니다.
7. 기존 파일의 관련 없는 사용자 변경을 덮어쓰지 않습니다.
8. SubAuth는 개발과 개발자가 통제하는 시연에만 사용합니다. 운영 설정을 SubAuth로
   바꾸지 않습니다.
9. HTTP API 경로를 임의로 `/chat`이라고 가정하지 않습니다. 기존 Controller를
   확인하고, 새 API가 필요하면 사용자에게 알립니다.

현재 배포 버전은 `0.2.0-internal.3`입니다.

## 1. 프로젝트 확인

다음 항목을 읽기 전용으로 확인합니다.

- 운영체제가 macOS인지
- `java -version`이 21 이상인지
- 프로젝트 루트에 `build.gradle`, `build.gradle.kts`, `pom.xml` 중 무엇이 있는지
- 현재 Spring Boot와 Spring AI 버전
- `application.yml`, `application.yaml`, `application.properties` 중 사용 중인 형식
- 기존 `ChatClient`, `ChatModel`, Controller와 개발·운영 프로필
- Git 작업 트리의 기존 변경사항

Spring Boot 3.5 + Spring AI 1.1 또는 Spring Boot 4.1 + Spring AI 2.0 조합이 아니면
파일을 수정하기 전에 호환성 문제를 사용자에게 알립니다.

## 2. GitHub Packages 준비

SubAuth 소스 저장소는 공개이지만 GitHub Packages의 Maven 패키지를 받으려면 인증이
필요합니다. 다음 명령으로 상태만 확인합니다.

```bash
gh auth status -h github.com
```

로그인이 없거나 `read:packages` 권한이 없다면 사용자가 다음 명령을 직접 실행하도록
안내하고 완료될 때까지 기다립니다.

```bash
gh auth login -h github.com -p https
gh auth refresh -h github.com -s read:packages
```

SubAuth 저장소가 로컬에 있다면 해당 저장소의 설정 스크립트를 사용합니다. 없다면
공개 저장소를 임시 폴더에 얕게 복제해 사용할 수 있습니다. 사용자의 동의를 받은 뒤
프로젝트의 빌드 도구에 맞는 명령 하나만 실행합니다.

Gradle:

```bash
./scripts/setup-internal.sh --yes --gradle-only --version 0.2.0-internal.3
```

Maven:

```bash
./scripts/setup-internal.sh --yes --maven-only --version 0.2.0-internal.3
```

이 스크립트가 수정하는 전역 파일은 Gradle의 `~/.gradle/gradle.properties` 또는
Maven의 `~/.m2/settings.xml`입니다. 이 파일들의 인증 값을 대화나 작업 결과에
출력하지 않습니다.

## 3. 빌드 파일 수정

### Gradle Groovy

먼저 저장소 선언 위치를 확인합니다. 프로젝트가 `build.gradle`의 `repositories`를
사용하면 그곳에, `settings.gradle`의 `dependencyResolutionManagement.repositories`를
사용하면 그곳에 다음 Maven 저장소를 병합합니다. 같은 저장소가 이미 있으면 중복으로
추가하지 않습니다.

```groovy
maven {
    name = 'SubAuthGitHubPackages'
    url = uri('https://maven.pkg.github.com/kchanis1223/SubAuth')

    content {
        includeGroup 'io.github.kchanis1223'
    }

    credentials {
        username = providers.gradleProperty('subauthGithubUser')
                .orElse(providers.environmentVariable('GITHUB_ACTOR')).orNull
        password = providers.gradleProperty('subauthGithubToken')
                .orElse(providers.environmentVariable('GITHUB_TOKEN')).orNull
    }
}
```

기존 `mavenCentral()`에는 다음 콘텐츠 필터를 병합합니다. 이 필터는 SubAuth 조회가
Maven Central로 넘어가 불필요한 실패나 요청을 만드는 것을 막습니다.

```groovy
mavenCentral {
    content {
        excludeGroup 'io.github.kchanis1223'
    }
}
```

기존 `dependencies`에 다음 한 줄을 추가합니다.

```groovy
implementation 'io.github.kchanis1223:subauth-spring-boot-starter:0.2.0-internal.3'
```

### Gradle Kotlin

저장소 선언은 프로젝트가 현재 사용하는 `build.gradle.kts` 또는
`settings.gradle.kts`의 `dependencyResolutionManagement.repositories`에 추가합니다.
Groovy 문법을 그대로 넣지 말고 다음 Kotlin DSL을 기존 블록에 병합합니다.

```kotlin
repositories {
    maven {
        name = "SubAuthGitHubPackages"
        url = uri("https://maven.pkg.github.com/kchanis1223/SubAuth")

        content {
            includeGroup("io.github.kchanis1223")
        }

        credentials {
            username = providers.gradleProperty("subauthGithubUser")
                .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
            password = providers.gradleProperty("subauthGithubToken")
                .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
        }
    }

    mavenCentral {
        content {
            excludeGroup("io.github.kchanis1223")
        }
    }
}

dependencies {
    implementation("io.github.kchanis1223:subauth-spring-boot-starter:0.2.0-internal.3")
}
```

### Maven: `pom.xml`

설정 스크립트가 GitHub Packages 저장소를 `~/.m2/settings.xml`에 구성하므로 기존
`dependencies`에 다음 항목만 추가합니다.

```xml
<dependency>
    <groupId>io.github.kchanis1223</groupId>
    <artifactId>subauth-spring-boot-starter</artifactId>
    <version>0.2.0-internal.3</version>
</dependency>
```

사용자 프로젝트의 Spring Boot 또는 Spring AI BOM을 SubAuth의 버전으로 바꾸지
않습니다.

## 4. 애플리케이션 설정

기존 프로젝트가 사용하는 설정 파일 형식을 유지합니다. 개발과 운영 프로필이 이미
분리되어 있다면 개발 프로필에만 적용합니다.

YAML:

```yaml
spring:
  ai:
    model:
      chat: subauth

    subauth:
      provider: ${SUBAUTH_PROVIDER:openai}
      model: ${SUBAUTH_MODEL:auto}
      effort: ${SUBAUTH_EFFORT:medium}
      unsupported-options: ${SUBAUTH_UNSUPPORTED_OPTIONS:ignore}
      request-timeout: 5m
      probe-timeout: 20s
```

Properties:

```properties
spring.ai.model.chat=subauth
spring.ai.subauth.provider=${SUBAUTH_PROVIDER:openai}
spring.ai.subauth.model=${SUBAUTH_MODEL:auto}
spring.ai.subauth.effort=${SUBAUTH_EFFORT:medium}
spring.ai.subauth.unsupported-options=${SUBAUTH_UNSUPPORTED_OPTIONS:ignore}
spring.ai.subauth.request-timeout=5m
spring.ai.subauth.probe-timeout=20s
```

기존 코드의 `temperature`, `maxTokens`, `topP` 같은 생성 옵션은 삭제하지 않아도
됩니다. 구독 런타임이 적용할 수 없는 옵션은 기본값으로 무시되며 응답 메타데이터의
`ignoredOptions`에 기록됩니다. Codex는 Spring AI `Media`의 PNG·JPEG 이미지 입력을
지원합니다. 그 밖의 이미지·파일·도구 호출은 요청 의미가 달라질 수 있어 계속
예외로 처리합니다.

기존 API 키 자리표시자가 값 없이 평가되어 애플리케이션 시작을 막는지 확인합니다.
운영용 API 설정은 삭제하지 말고 운영 프로필에 유지합니다.

기존 서비스가 `ChatClient`를 사용한다면 Java 코드는 바꾸지 않습니다. SubAuth는
별도의 HTTP API를 만들지 않습니다.

## 5. 공급자 인증

사용자가 선택한 공급자 하나만 확인합니다. 선택이 없으면 OpenAI, Claude, Gemini 중
무엇을 사용할지 먼저 묻습니다. 로그인 브라우저, 계정 선택, 약관 동의는 사용자가
직접 수행하도록 안내합니다.

OpenAI 구독:

```bash
codex --version
codex login
codex login status
```

상태가 API 키가 아닌 ChatGPT 로그인인지 확인합니다.

Claude 구독:

```bash
claude --version
claude auth login
claude auth status --json
```

`loggedIn`이 `true`이고 Claude.ai 구독 로그인이 선택되었는지 확인합니다. Claude
연결은 개발과 제한된 시연 용도임을 사용자에게 알립니다.

Gemini 구독:

```bash
agy --version
agy
agy models
```

처음 실행할 때 `Google OAuth`를 선택합니다. `agy models`가 모델 목록을 출력해야
하며 Antigravity의 `Use AI Credits` 설정은 꺼져 있어야 합니다. 제3자를 위한 서비스에
구독 모델을 사용하는 것은 약관상 권장되지 않으며, 사용 전에 Google의 최신 약관과
사용 범위를 직접 확인해야 한다고 알립니다.

CLI는 설치되어 있지만 Spring Boot가 찾지 못하면 Gradle 데몬을 종료한 뒤 같은
터미널에서 다시 실행합니다.

```bash
./gradlew --stop
```

## 6. 검증

Gradle 프로젝트:

```bash
./gradlew dependencyInsight --dependency subauth-spring-boot-starter --configuration runtimeClasspath
./gradlew compileJava
```

Maven 프로젝트:

```bash
mvn dependency:tree -Dincludes=io.github.kchanis1223
mvn compile
```

컴파일이 성공하면 선택한 공급자로 애플리케이션을 실행합니다.

Gradle:

```bash
SUBAUTH_PROVIDER=openai ./gradlew bootRun
```

Maven:

```bash
SUBAUTH_PROVIDER=openai mvn spring-boot:run
```

`openai`는 사용자가 선택한 경우 `claude` 또는 `gemini`로 바꿉니다. 실행 후에는
기존 Controller의 실제 경로로 간단한 텍스트 요청을 보냅니다. Controller가 없다면
SubAuth 자체의 문제가 아니며, 테스트용 API를 추가할지 사용자에게 물어봅니다.

## 7. 작업 결과 보고

작업을 마친 AI는 다음 내용을 짧게 보고합니다.

- 감지한 빌드 도구와 Spring Boot/Spring AI 버전
- 수정한 파일
- 선택한 공급자와 인증 준비 상태
- 실행한 검증 명령과 결과
- 사용자가 직접 해야 하는 남은 명령
- 개발·시연 용도 제한과 운영 전환 필요성

토큰 값, 인증 파일 내용, 계정의 민감한 정보는 보고하지 않습니다.
