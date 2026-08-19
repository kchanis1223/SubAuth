# GitHub Packages distribution

SubAuth is distributed through the public GitHub Packages Maven registry
attached to `kchanis1223/SubAuth`. Preview package versions currently use the
form `0.2.0-internal.1`; the `internal` suffix identifies the release channel,
not repository visibility.

## Publisher workflow

The publish workflow runs for tags matching `v*-internal.*`. It validates the
tag, applies the tag value as the Maven reactor version, runs all non-live
tests, and deploys the parent plus all three modules with the repository's
`GITHUB_TOKEN`.

Create a release only from a clean, verified `main` commit:

```bash
mvn clean verify
git tag v0.2.0-internal.1
git push origin v0.2.0-internal.1
```

Published GitHub Packages versions are treated as immutable. If a package is
wrong, fix it and publish `v0.2.0-internal.2`; do not move or reuse a published
tag.

The live provider tests remain disabled in GitHub Actions. Before tagging,
run them manually on a developer-controlled Mac with the required subscription
runtimes logged in:

```bash
SUBAUTH_LIVE_TESTS=true mvn -pl subauth-spring-ai test
```

## Consumer setup

GitHub Packages Maven downloads require authentication even when the package and
repository are public. Authenticate GitHub CLI and grant its token package-read
access:

```bash
gh auth login
gh auth refresh -h github.com -s read:packages
```

Run the setup from a SubAuth checkout:

```bash
scripts/setup-internal.sh --version 0.2.0-internal.3
```

The setup checks macOS, Java, GitHub authentication, and the three provider
CLIs. By default it configures both build tools:

- Maven: backs up `~/.m2/settings.xml`, adds the active
  `subauth-github-packages` repository profile, and stores the credential.
- Gradle: backs up `~/.gradle/gradle.properties` and upserts
  `subauthGithubUser` and `subauthGithubToken` while preserving unrelated
  properties and comments.

All credential files and backups are restricted to file mode `0600`. Maven
package verification downloads the requested starter and transitive modules.
Gradle-only verification checks the published starter POM directly without
requiring a global Gradle installation.

Configure only one build tool when needed:

```bash
scripts/setup-internal.sh --maven-only
scripts/setup-internal.sh --gradle-only
```

For non-interactive setup, provide a classic PAT with `read:packages` without
putting it on the command line:

```bash
export SUBAUTH_GITHUB_TOKEN="..."
scripts/setup-internal.sh --yes --version 0.2.0-internal.3
unset SUBAUTH_GITHUB_TOKEN
```

The public `kchanis1223/SubAuth` repository can be cloned without repository
access. A GitHub account and package-read authentication are still required to
download Maven packages. If an organization enforces SSO on the selected token,
authorize the token for that organization as well.

After setup, consumer projects only add the starter dependency; the Maven
repository comes from the active settings profile:

```xml
<dependency>
    <groupId>io.github.kchanis1223</groupId>
    <artifactId>subauth-spring-boot-starter</artifactId>
    <version>0.2.0-internal.3</version>
</dependency>
```

Gradle credentials are configured globally, but the SubAuth package repository
must remain explicit in each Gradle build. This avoids injecting a project-specific
repository into every unrelated Gradle project:

```groovy
repositories {
    mavenCentral()
    maven {
        url = uri('https://maven.pkg.github.com/kchanis1223/SubAuth')
        credentials {
            username = findProperty('subauthGithubUser')
                    ?: System.getenv('GITHUB_ACTOR')
            password = findProperty('subauthGithubToken')
                    ?: System.getenv('GITHUB_TOKEN')
        }
    }
}

dependencies {
    implementation 'io.github.kchanis1223:subauth-spring-boot-starter:0.2.0-internal.3'
}
```

Keep the Spring AI BOM already selected by the host application. Tested
combinations are Spring Boot 3.5 with Spring AI 1.1 and Spring Boot 4.1 with
Spring AI 2.0. A new application without Spring AI dependency management must
import the BOM matching its Spring Boot generation; installing SubAuth must not
silently upgrade the application's framework.

The release workflow rejects consumer POMs that import the SubAuth build
baseline BOMs. This keeps package installation from resolving the full Spring
Boot 4 dependency-management graph in Spring Boot 3 applications.

Never commit `subauthGithubToken` to a project-level `gradle.properties`; setup
stores it only in the user's home directory.

CI in a Maven consumer repository should configure the same repository and
server credentials using `actions/setup-java`. Gradle consumers should pass
`GITHUB_ACTOR` and `GITHUB_TOKEN` to the repository credentials block. In both
cases, grant the consumer repository read access to the package. Do not copy a
developer's personal subscription credentials into CI; SubAuth live provider
calls remain a local macOS release gate.
