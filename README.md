# SubAuth

SubAuth는 Spring AI 애플리케이션을 개발할 때 API 키 대신 개발자의 AI 구독을
사용할 수 있게 해 주는 `ChatModel`입니다.

애플리케이션은 기존처럼 `ChatClient`를 사용합니다. SubAuth는 모델을 호출하는
부분만 Codex, Claude Code 또는 Antigravity 런타임으로 바꿉니다.

```text
Spring Boot 애플리케이션
    -> Spring AI ChatClient
        -> SubAuthChatModel
            -> Codex          (OpenAI 구독)
            -> Claude Code    (Claude 구독)
            -> Antigravity    (Gemini 구독)
```

정식 API를 연결하기 전의 개발과 내부 시연이 주 사용 범위입니다. 정식 운영에서는
각 공급자가 지원하는 API 인증 방식으로 전환해야 합니다.

## 지원 환경

- macOS
- Java 21 이상
- Spring Boot 3.5 + Spring AI 1.1
- Spring Boot 4.1 + Spring AI 2.0
- SubAuth 저장소와 GitHub Packages에 접근할 수 있는 GitHub 계정
- Codex, Claude Code, Antigravity 중 하나 이상의 로그인된 런타임

현재 배포 버전은 `0.2.0-internal.2`입니다.

적용할 때 바꾸는 것은 세 가지입니다.

1. 사용자 컴퓨터에 GitHub Packages 인증을 한 번 설정합니다.
2. 프로젝트에 SubAuth Starter를 추가합니다.
3. `application.yml`에서 기본 `ChatModel`을 `subauth`로 선택합니다.

기존 `ChatClient` 코드는 바꾸지 않습니다.

## 1. 설치 준비

GitHub CLI로 로그인하고 내부 패키지를 읽을 수 있는 권한을 추가합니다.

```bash
brew install gh
gh auth login
gh auth refresh -h github.com -s read:packages
```

SubAuth 저장소를 받습니다.

```bash
git clone https://github.com/kchanis1223/SubAuth.git
cd SubAuth
```

Gradle 프로젝트를 사용한다면 다음 명령을 실행합니다.

```bash
./scripts/setup-internal.sh --gradle-only --version 0.2.0-internal.2
```

Maven 프로젝트를 사용한다면 다음 명령을 실행합니다.

```bash
./scripts/setup-internal.sh --maven-only --version 0.2.0-internal.2
```

이 스크립트는 다음 항목을 확인하고 설정합니다.

- macOS와 Java 버전
- GitHub Packages 인증
- Codex, Claude Code, Antigravity 설치 및 로그인 상태
- SubAuth 패키지 다운로드 가능 여부

Gradle 인증 정보는 `~/.gradle/gradle.properties`, Maven 인증 정보는
`~/.m2/settings.xml`에 저장됩니다. 프로젝트 안에 인증 정보를 작성하지 않습니다.

GitHub 인증과 AI 구독 인증은 용도가 다릅니다.

```text
GitHub 인증      -> SubAuth 라이브러리 다운로드
AI 구독 로그인  -> 실제 모델 호출
```

## 2. 프로젝트에 SubAuth 추가

### Gradle

기존 `build.gradle`의 `repositories`에 SubAuth 저장소를 추가합니다.

```groovy
repositories {
    maven {
        name = 'SubAuthGitHubPackages'
        url = uri('https://maven.pkg.github.com/kchanis1223/SubAuth')

        content {
            includeGroup 'io.github.kchanis1223'
        }

        credentials {
            username = project.findProperty('subauthGithubUser')
                    ?: System.getenv('GITHUB_ACTOR')
            password = project.findProperty('subauthGithubToken')
                    ?: System.getenv('GITHUB_TOKEN')
        }
    }

    mavenCentral {
        content {
            excludeGroup 'io.github.kchanis1223'
        }
    }
}
```

`dependencies`에 Starter를 추가합니다.

```groovy
dependencies {
    implementation 'io.github.kchanis1223:subauth-spring-boot-starter:0.2.0-internal.2'
}
```

### Maven

설정 스크립트를 실행했다면 `pom.xml`에는 Starter만 추가하면 됩니다.

```xml
<dependency>
    <groupId>io.github.kchanis1223</groupId>
    <artifactId>subauth-spring-boot-starter</artifactId>
    <version>0.2.0-internal.2</version>
</dependency>
```

기존 프로젝트가 사용하는 Spring Boot와 Spring AI BOM은 변경하지 않습니다.
SubAuth는 프로젝트가 선택한 Spring AI 버전에 맞춰 동작합니다.

## 3. application.yml 설정

`src/main/resources/application.yml`에 다음 설정을 추가합니다.

```yaml
spring:
  ai:
    model:
      chat: subauth

    subauth:
      provider: ${SUBAUTH_PROVIDER:openai}
      model: ${SUBAUTH_MODEL:auto}
      effort: ${SUBAUTH_EFFORT:medium}
      request-timeout: 5m
      probe-timeout: 20s
```

기본 설정은 OpenAI, 자동 모델 선택, `medium` 추론 강도입니다. 실행할 때 환경변수로
공급자와 모델을 변경할 수 있습니다.

기존 설정에 `${OPENAI_API_KEY}` 같은 API 키 자리표시자가 있다면 개발 설정에서
제거하거나 운영용 설정 파일로 이동합니다. 값이 없는 자리표시자가 남아 있으면
애플리케이션 시작이 실패할 수 있습니다.

## 4. Java 코드 작성

SubAuth 전용 호출 코드는 필요하지 않습니다. 기존 Spring AI `ChatClient` 코드를
그대로 사용합니다.

```java
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class AiService {
    private final ChatClient chat;

    public AiService(ChatClient.Builder builder) {
        this.chat = builder
                .defaultSystem("너는 정확하고 친절한 도우미다.")
                .build();
    }

    public String ask(String message) {
        return chat.prompt()
                .user(message)
                .call()
                .content();
    }
}
```

## 5. 공급자 로그인과 실행

사용할 공급자의 CLI 로그인을 먼저 완료합니다.

| 공급자 | 로그인 확인 | Gradle 실행 |
|---|---|---|
| OpenAI | `codex login status` | `SUBAUTH_PROVIDER=openai ./gradlew bootRun` |
| Claude | `claude auth status --json` | `SUBAUTH_PROVIDER=claude ./gradlew bootRun` |
| Gemini | `agy models` | `SUBAUTH_PROVIDER=gemini ./gradlew bootRun` |

OpenAI는 Codex CLI에서 `codex login`으로 로그인할 수 있습니다. Claude와 Gemini는
각 CLI의 일반 로그인 절차를 사용합니다.

Maven 프로젝트는 `./gradlew bootRun` 대신 프로젝트에서 사용하던 Maven 실행 명령을
사용합니다.

```bash
SUBAUTH_PROVIDER=openai mvn spring-boot:run
```

Claude 구독 연결은 개발 및 개발자가 통제하는 시연에만 사용합니다. 정식 운영
전에는 Anthropic이 지원하는 API 인증으로 전환해야 합니다. Antigravity 연결은
Google의 허가 없이 외부 사용자나 운영 트래픽에 사용하지 않습니다.

## 6. 동작 확인

애플리케이션이 8080 포트에서 실행 중이라면 새 터미널에서 호출합니다.

```bash
curl -sS --get 'http://localhost:8080/chat' --data-urlencode 'message=Reply exactly: SUBAUTH_OK'
```

다음 응답이 나오면 Spring AI에서 구독 런타임까지의 연결이 정상입니다.

```text
SUBAUTH_OK
```

8080 포트를 다른 프로그램이 사용 중이라면 다른 포트로 실행할 수 있습니다.

```bash
SERVER_PORT=8081 SUBAUTH_PROVIDER=openai ./gradlew bootRun
```

## 대화와 스트리밍

SubAuth는 대화 내용을 저장하지 않습니다. 대화 이력은 기존 Spring AI
`ChatMemory`나 메인 서비스의 데이터베이스에서 관리합니다. SubAuth는 `Prompt`에
포함된 전체 메시지를 매 요청마다 공급자 런타임에 전달합니다.

스트리밍은 기존 `chat.prompt().user(message).stream().content()`를 그대로 사용합니다.
스트림이 취소되면 Codex 작업을 중단하거나 Claude·Gemini 자식 프로세스를 종료합니다.

## 개발과 운영 전환

개발용 `application-dev.yml`에서는 `spring.ai.model.chat=subauth`를 선택합니다.
운영용 `application-prod.yml`에서는 공식 Spring AI 공급자와 API 키를 설정합니다.
서비스 코드는 두 환경 모두 `ChatClient`를 사용하므로 바꾸지 않습니다. 운영 빌드에는
선택한 공식 Spring AI Starter가 필요합니다.

## 현재 지원 범위

| 지원 | 아직 지원하지 않음 |
|---|---|
| 텍스트 메시지 | 이미지, 음성, 파일 입력 |
| 동기 호출과 스트리밍 | Tool Callback과 MCP |
| OpenAI, Claude, Gemini 선택 | 공급자 세션 이어쓰기 |
| 모델과 effort 선택 | temperature, top-p, max-token 등의 생성 옵션 |
| 요청 취소와 제한 시간 | API 키 기반 운영 전송 |

지원하지 않는 옵션은 무시하지 않고 `SubAuthUnsupportedCapabilityException`으로
알립니다.

## 문제 해결

### GitHub Packages 다운로드 실패

```bash
gh auth refresh -h github.com -s read:packages
./scripts/setup-internal.sh --gradle-only --version 0.2.0-internal.2
```

Gradle을 사용한다면 `build.gradle`에 SubAuth 저장소가 있는지도 확인합니다.

### `subscription_not_ready`

선택한 런타임의 로그인을 확인합니다.

```bash
codex login status
claude auth status --json
agy models
```

### `SubAuthUnsupportedCapabilityException`

기존 공급자 설정에서 `temperature`, `max-tokens` 같은 옵션을 제거하고
`provider`, `model`, `effort`만 사용해 다시 확인합니다.

### Maven Central `429 Too Many Requests`

Maven Central의 임시 요청 제한입니다. `--refresh-dependencies`를 반복하지 말고
잠시 기다린 뒤 일반 명령으로 다시 실행합니다. `mavenLocal()`은 테스트가 끝난 뒤
제거합니다.

### `Port 8080 was already in use`

```bash
SERVER_PORT=8081 SUBAUTH_PROVIDER=openai ./gradlew bootRun
```

## 내부 문서

구조와 정책의 자세한 내용은 다음 문서에서 확인할 수 있습니다.

- [아키텍처](docs/architecture.md)
- [Spring AI 호환성](docs/compatibility.md)
- [공급자 정책](docs/runtime-policies.md)
- [내부 배포](docs/internal-distribution.md)
