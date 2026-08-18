# SubAuth

SubAuth는 Spring AI 애플리케이션이 **API 키 대신 개발자의 AI 구독 로그인**을
사용해 개발·테스트할 수 있게 해 주는 Spring AI `ChatModel` 구현체입니다.

애플리케이션 코드는 평소처럼 `ChatClient`를 사용합니다. SubAuth는 그 아래의
모델 전송 계층만 Codex, Claude Code 또는 Antigravity 런타임으로 바꿉니다.

```text
Spring Boot 애플리케이션
    -> Spring AI ChatClient
        -> SubAuthChatModel
            -> Codex App Server       (OpenAI / ChatGPT 구독)
            -> Claude Code            (Claude 구독, experimental)
            -> Antigravity            (Gemini 구독, terms-restricted)
```

SubAuth는 HTTP 프록시, OpenAI 호환 API 서버, Python SDK 또는 외부 사용자 인증
시스템이 아닙니다. Spring Boot 애플리케이션 프로세스 안에서 동작하는 Spring AI
라이브러리이며, 실제 추론 시 로그인된 공식 개발자 런타임을 자식 프로세스로
사용합니다.

## 언제 사용하나

적합한 용도:

- 정식 API 키를 발급하기 전 Spring AI 기능 개발
- 개발자 개인의 구독으로 프롬프트와 서비스 로직 검증
- 개발자가 통제하는 소규모 내부 시연
- 같은 `ChatClient` 코드로 OpenAI, Claude, Gemini 비교

적합하지 않은 용도:

- 불특정 외부 사용자를 대상으로 하는 정식 운영 서비스
- 구독 계정을 조직 공용 API처럼 제공하는 서비스
- Provider 약관이 허용하지 않는 트래픽 중계
- API 사용량, SLA, 조직 감사가 필요한 운영 환경

정식 운영에서는 Spring AI의 공식 Provider Starter와 지원되는 API 인증으로
전환하는 것을 원칙으로 합니다.

## 지원 환경

- macOS
- Java 21 이상
- Spring Boot 3.5 + Spring AI 1.1 또는 Spring Boot 4.1 + Spring AI 2.0
- SubAuth GitHub 저장소와 GitHub Packages에 접근할 수 있는 계정
- 다음 중 하나 이상의 로그인된 런타임
  - OpenAI: Codex CLI
  - Claude: Claude Code
  - Gemini: Antigravity CLI (`agy`)

현재 내부 배포 버전은 `0.2.0-internal.2`입니다.

## 처음 설치하기

### 1. GitHub CLI 인증

GitHub CLI가 없다면 먼저 설치합니다.

```bash
brew install gh
```

GitHub에 로그인하고 private package를 읽을 수 있는 권한을 부여합니다.

```bash
gh auth login
gh auth refresh -h github.com -s read:packages
```

인증 상태를 확인합니다.

```bash
gh auth status
```

### 2. SubAuth 저장소 받기

```bash
git clone git@github.com:kchanis1223/SubAuth.git
cd SubAuth
```

이미 저장소가 있다면 최신 `main`을 사용합니다.

```bash
git pull --ff-only
```

### 3. 내부 패키지 설정

Gradle 프로젝트만 사용한다면 다음을 실행합니다.

```bash
./scripts/setup-internal.sh --gradle-only --version 0.2.0-internal.2
```

Maven 프로젝트만 사용한다면:

```bash
./scripts/setup-internal.sh --maven-only --version 0.2.0-internal.2
```

Gradle과 Maven을 모두 설정하려면:

```bash
./scripts/setup-internal.sh --version 0.2.0-internal.2
```

설정 스크립트는 다음 작업을 수행합니다.

- macOS와 Java 21 확인
- GitHub Packages 읽기 권한 확인
- Gradle 사용 시 `~/.gradle/gradle.properties`에 package 인증 정보 저장
- Maven 사용 시 `~/.m2/settings.xml`에 package 저장소와 인증 정보 저장
- Codex, Claude Code, Antigravity 설치·로그인 상태 확인
- 지정한 SubAuth 패키지 버전 다운로드 가능 여부 확인

GitHub Packages 인증은 SubAuth JAR를 다운로드하기 위한 인증입니다. AI를 실제로
호출하는 Provider 구독 인증과는 별개입니다.

```text
GitHub 인증       -> SubAuth 라이브러리 다운로드
Provider 로그인   -> 실제 AI 추론 실행
```

## Gradle 프로젝트에 적용하기

기존 프로젝트의 `build.gradle`에 GitHub Packages 저장소를 추가합니다.

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

기존 Spring AI BOM과 Spring Boot 버전은 그대로 유지합니다. SubAuth를 사용하기
위해 애플리케이션의 Spring Boot나 Spring AI를 강제로 업그레이드하지 않습니다.

의존성이 정상적으로 연결됐는지 확인할 수 있습니다.

```bash
./gradlew dependencyInsight --dependency subauth-spring-boot-starter --configuration runtimeClasspath
./gradlew dependencyInsight --dependency spring-ai-model --configuration runtimeClasspath
```

출력에서 SubAuth 버전에 `FAILED`가 없고, `spring-ai-model`이 기존 프로젝트 BOM의
버전으로 선택되면 정상입니다.

## Maven 프로젝트에 적용하기

설정 스크립트를 실행했다면 프로젝트 `pom.xml`에는 Starter만 추가하면 됩니다.

```xml
<dependency>
    <groupId>io.github.kchanis1223</groupId>
    <artifactId>subauth-spring-boot-starter</artifactId>
    <version>0.2.0-internal.2</version>
</dependency>
```

기존 Spring AI BOM은 유지합니다. Maven 패키지 연결을 확인하려면 다음을 실행합니다.

```bash
mvn dependency:tree -Dincludes=io.github.kchanis1223
```

주의: zsh에서 줄을 나눌 때 `\` 뒤에는 공백을 넣으면 안 됩니다. 익숙하지 않다면
위와 같이 명령을 한 줄로 실행하는 것이 안전합니다.

## application.yml 설정

`src/main/resources/application.yml`에서 SubAuth를 기본 ChatModel로 선택합니다.

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

이 설정의 의미는 다음과 같습니다.

| 설정 | 기본값 | 설명 |
|---|---:|---|
| `provider` | `openai` | `openai`, `claude`, `gemini` 중 선택 |
| `model` | `auto` | 런타임이 모델을 선택하거나 특정 모델 지정 |
| `effort` | `medium` | 추론 강도 |
| `request-timeout` | `5m` | 실제 AI 요청의 최대 실행 시간 |
| `probe-timeout` | `20s` | CLI 설치·로그인·모델 확인 제한 시간 |

기존 `spring.ai.openai.api-key`처럼 API 키를 요구하는 설정이 있다면 개발용
SubAuth 프로파일에서는 제거하거나 운영 프로파일로 이동합니다. 해석되지 않는
`${OPENAI_API_KEY}` 자리표시자가 남아 있으면 SubAuth와 관계없이 애플리케이션
기동이 실패할 수 있습니다.

## Java 코드는 평소처럼 작성하기

SubAuth 전용 Controller나 SDK를 사용할 필요가 없습니다. 일반적인 Spring AI
`ChatClient` 코드를 그대로 사용합니다.

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

간단한 테스트 Controller 예시는 다음과 같습니다.

```java
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {
    private final AiService aiService;

    public ChatController(AiService aiService) {
        this.aiService = aiService;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        return aiService.ask(message);
    }
}
```

## Provider 로그인과 실행

### OpenAI / Codex

Codex CLI를 설치하고 ChatGPT 구독 계정으로 로그인합니다.

```bash
npm install -g @openai/codex
codex login
codex login status
```

애플리케이션을 실행합니다.

```bash
SUBAUTH_PROVIDER=openai ./gradlew bootRun
```

SubAuth는 `codex app-server --stdio`를 시작하고 ChatGPT 계정 유형인지 확인합니다.
요청마다 읽기 전용 ephemeral thread를 사용하고 승인이 필요한 행동은 허용하지
않습니다.

### Claude / Claude Code

Claude Code를 설치하고 정상적인 Claude.ai 로그인을 완료합니다.

```bash
claude auth status --json
```

애플리케이션을 실행합니다.

```bash
SUBAUTH_PROVIDER=claude ./gradlew bootRun
```

SubAuth는 Tool, MCP, Slash Command, 세션 저장을 끄고 safe mode에서 Claude Code를
실행합니다. API 키, Bedrock, Vertex, Foundry 관련 환경변수도 자식 프로세스에서
제거하여 API 과금으로 조용히 전환되는 것을 막습니다.

Claude 구독 연결은 `experimental`입니다. 개발 및 개발자가 통제하는 제한된
미리보기에서만 사용하고 정식 릴리스 전 Anthropic의 지원되는 API 인증으로
전환해야 합니다.

### Gemini / Antigravity

Antigravity CLI에서 Google 로그인을 완료하고 모델 목록을 확인합니다.

```bash
agy models
```

애플리케이션을 실행합니다.

```bash
SUBAUTH_PROVIDER=gemini ./gradlew bootRun
```

SubAuth는 Gemini API 키, ADC, Vertex 및 Google Cloud 환경변수를 제거합니다.
유료 AI Credit fallback이 활성화되어 있으면 실행을 거부하고, Antigravity가 Tool
호출을 시도하면 요청을 중단합니다.

Antigravity 연결은 `terms-restricted`입니다. Google의 별도 허가 없이 외부 사용자나
운영 트래픽에 사용하지 마십시오.

## 실제 호출 확인

애플리케이션이 8080 포트에서 실행 중이라면 새 터미널에서 호출합니다.

```bash
curl -sS --get 'http://localhost:8080/chat' --data-urlencode 'message=Reply exactly: SUBAUTH_OK'
```

다음 응답이 나오면 전체 경로가 정상입니다.

```text
SUBAUTH_OK
```

포트가 이미 사용 중이면 임시로 다른 포트를 선택할 수 있습니다.

```bash
SERVER_PORT=8081 SUBAUTH_PROVIDER=openai ./gradlew bootRun
```

이 경우 호출 주소도 `http://localhost:8081/chat`으로 바꿉니다.

## Provider와 모델 바꾸기

Java 코드를 수정하지 않고 실행 환경변수만 변경할 수 있습니다.

```bash
SUBAUTH_PROVIDER=openai SUBAUTH_MODEL=auto SUBAUTH_EFFORT=medium ./gradlew bootRun
SUBAUTH_PROVIDER=claude SUBAUTH_MODEL=auto SUBAUTH_EFFORT=high ./gradlew bootRun
SUBAUTH_PROVIDER=gemini SUBAUTH_MODEL=auto SUBAUTH_EFFORT=medium ./gradlew bootRun
```

지원 effort:

| Provider | effort |
|---|---|
| OpenAI | `minimal`, `low`, `medium`, `high`, `xhigh`, `max` |
| Claude | `low`, `medium`, `high`, `xhigh`, `max` |
| Gemini | `low`, `medium`, `high` |

Gemini는 사용 가능한 모델 이름과 effort suffix가 일치해야 합니다. `model=auto`를
사용하면 SubAuth가 `agy models` 결과에서 맞는 모델을 선택합니다.

## 스트리밍

`ChatClient`의 일반 스트리밍 API를 그대로 사용합니다.

```java
import reactor.core.publisher.Flux;

public Flux<String> stream(String message) {
    return chat.prompt()
            .user(message)
            .stream()
            .content();
}
```

브라우저가 연결을 끊거나 Reactor 구독이 취소되면 SubAuth는 가능한 범위에서 실제
Provider 작업도 취소합니다.

- OpenAI: Codex turn에 interrupt 전송
- Claude/Gemini: 실행 중인 자식 프로세스 종료
- timeout: 런타임 프로세스 종료

## 대화 메모리와 세션

SubAuth는 애플리케이션 관점에서 stateless입니다. Provider의 native session ID를
다음 요청의 대화 ID로 사용하지 않습니다.

대화 상태는 메인 서비스의 Spring AI `ChatMemory`나 데이터베이스에서 관리합니다.

```text
conversationId
    -> ChatMemory에서 과거 메시지 조회
    -> Prompt에 전체 대화 이력 추가
    -> SubAuth가 독립 요청으로 실행
```

따라서 Provider를 변경해도 애플리케이션의 대화 ID와 메모리 정책은 유지됩니다.

## 기존 공식 Provider와 개발·운영 전환

비즈니스 코드는 `ChatClient`, `Prompt`, `ChatResponse` 같은 portable Spring AI
타입만 사용하도록 유지합니다.

개발 프로파일에서는 SubAuth를 사용합니다.

```yaml
# application-dev.yml
spring:
  ai:
    model:
      chat: subauth
    subauth:
      provider: openai
      model: auto
      effort: medium
```

운영 프로파일에서는 공식 Provider를 사용합니다.

```yaml
# application-prod.yml
spring:
  ai:
    model:
      chat: openai
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: your-production-model
```

운영 빌드에는 선택한 공식 Spring AI Provider Starter가 필요합니다. SubAuth Starter는
OpenAI, Anthropic 또는 Google의 API 전송 라이브러리를 자동으로 추가하지 않습니다.

`spring.ai.model.chat=subauth`일 때 자동 구성되는 Bean 이름은
`subAuthChatModel`이며 기본 `@Primary` ChatModel이 됩니다. 다른 공식 또는 사용자
정의 ChatModel도 `@Qualifier`로 함께 사용할 수 있습니다.

## 호출별 Provider 변경

고급 사용자는 특정 `Prompt`에서 Provider, 모델, effort를 덮어쓸 수 있습니다.

```java
Prompt prompt = new Prompt(
        "Reply exactly: SUBAUTH_OK",
        SubAuthChatOptions.builder()
                .provider(SubAuthProvider.CLAUDE)
                .model("sonnet")
                .effort(SubAuthEffort.HIGH)
                .build()
);

ChatResponse response = chatModel.call(prompt);
```

구독 런타임이 확실하게 지원하지 못하는 일반 generation 옵션은 조용히 무시하지
않고 `SubAuthUnsupportedCapabilityException`으로 거부합니다.

현재 portable 지원 대상이 아닌 옵션과 기능:

- temperature, top-p, penalty, max-token, stop sequence
- Spring AI Tool Callback
- 이미지·음성·파일 입력
- Provider native session continuation
- Provider native structured-output 보장
- API 키 기반 운영 전송

## Health 확인

Spring Boot Actuator health 기능이 classpath에 있으면 SubAuth Health Indicator가
Provider 준비 상태를 제공합니다.

주요 정보:

- Provider CLI 실행 가능 여부
- 구독 로그인 준비 여부
- 사용 가능한 모델
- Provider 정책 상태

Health는 로그인이 준비됐는지 확인할 뿐, 외부 사용자 인증이나 서비스 권한 검사를
대신하지 않습니다.

## 자주 발생하는 문제

### `401`, `403` 또는 GitHub Packages 다운로드 실패

GitHub Packages 읽기 권한을 다시 확인합니다.

```bash
gh auth refresh -h github.com -s read:packages
./scripts/setup-internal.sh --gradle-only --version 0.2.0-internal.2
```

토큰을 프로젝트의 `gradle.properties`에 직접 커밋하지 마십시오. 설정 스크립트는
사용자 홈의 `~/.gradle/gradle.properties`만 수정합니다.

### Maven Central `429 Too Many Requests`

Maven Central이 현재 네트워크의 반복 다운로드를 임시 제한한 상태입니다. SubAuth
인증 오류와는 다릅니다.

- `--refresh-dependencies`를 반복하지 않습니다.
- 짧은 간격으로 계속 재시도하지 않습니다.
- 제한이 풀린 뒤 일반 명령으로 다시 실행합니다.
- `mavenLocal()`을 영구 설정으로 남기지 않습니다.

`0.2.0-internal.2`에서는 SubAuth 부모 POM의 framework BOM 해석 중 429가 나타날 수
있습니다. 이 문제는 `main`에서 제거됐으며 다음 내부 릴리스부터 SubAuth 소비자
POM이 Boot 4/Spring AI 2 BOM 전체를 import하지 않습니다.

### `subscription_not_ready`

선택한 Provider CLI가 설치됐지만 구독 로그인이 준비되지 않은 상태입니다.

```bash
codex login status
claude auth status --json
agy models
```

### `command not found`

CLI가 PATH에 없다면 절대 경로를 설정할 수 있습니다.

```yaml
spring:
  ai:
    subauth:
      commands:
        codex: /absolute/path/to/codex
        claude: /absolute/path/to/claude
        gemini: /absolute/path/to/agy
```

SubAuth를 프로젝트 폴더 안에 설치했더라도 실행 파일이 PATH에 있거나 위 경로를
지정하면 동작합니다. SubAuth 저장소 위치와 Provider CLI 설치 위치는 서로 독립입니다.

### `SubAuthUnsupportedCapabilityException`

현재 구독 런타임이 전달을 보장하지 못하는 옵션이 Prompt에 포함된 상태입니다.
기존 공식 Provider 설정의 `temperature`, `max-tokens` 같은 옵션을 제거하고
`provider`, `model`, `effort`부터 사용합니다.

### `Port 8080 was already in use`

SubAuth 오류가 아니라 기존 서버가 같은 포트를 사용 중입니다.

```bash
SERVER_PORT=8081 SUBAUTH_PROVIDER=openai ./gradlew bootRun
```

### `dependencyInsight`는 `BUILD SUCCESSFUL`인데 의존성에 `FAILED` 표시

`dependencyInsight` 보고서 작업 자체가 성공했을 뿐, 해당 의존성 해석은 실패한
상태입니다. 출력의 `FAILED` 원인을 확인해야 합니다.

## 보안과 책임 경계

SubAuth는 다음 안전장치를 적용합니다.

- Provider API 키 및 cloud credential 환경변수 제거
- Codex ephemeral read-only thread와 approval `never`
- Claude Tool, MCP, Slash Command, 세션 저장 비활성화
- Gemini sandbox 사용 및 Tool 이벤트 차단
- Prompt와 응답 본문을 SubAuth 로그에 기록하지 않음
- 자식 프로세스 stderr를 사용자 응답으로 전달하지 않음

하지만 SubAuth와 Provider CLI는 Spring Boot 애플리케이션과 같은 macOS 사용자
권한으로 실행됩니다. 메인 서비스는 다음 사항을 직접 책임져야 합니다.

- 외부 사용자 인증과 권한
- Rate Limit과 동시 요청 제한
- 입력·출력 안전 필터
- 대화 저장과 개인정보 보존·삭제 정책
- 감사 로그와 사용량 정책
- 운영 환경의 공식 API 전환

## 개발자와 유지보수자

로컬 빌드:

```bash
mvn test
mvn install
```

Spring AI 1.1 호환성 테스트:

```bash
gradle --no-daemon -p compatibility-tests/spring-ai-1.1 test
```

배포 POM 검사:

```bash
./scripts/verify-consumer-poms.sh
```

모듈 구성:

- `subauth-spring-ai`: `SubAuthChatModel`, 요청·응답 매핑, Provider Adapter
- `subauth-spring-boot-autoconfigure`: Properties, Bean, Health 자동 구성
- `subauth-spring-boot-starter`: 애플리케이션이 추가하는 Starter

더 자세한 내부 문서:

- [아키텍처](docs/architecture.md)
- [Spring AI 호환성](docs/compatibility.md)
- [Provider 정책](docs/runtime-policies.md)
- [내부 배포](docs/internal-distribution.md)
