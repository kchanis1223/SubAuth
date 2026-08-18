# Internal distribution

SubAuth is distributed inside the organization through the private GitHub
Packages Maven registry attached to `kchanis1223/SubAuth`. Internal package
versions use the form `0.2.0-internal.1`.

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

## Internal consumer setup

GitHub Packages requires authentication for private Maven packages. Authenticate
GitHub CLI and grant its token package-read access:

```bash
gh auth login
gh auth refresh -h github.com -s read:packages
```

Run the setup from a SubAuth checkout:

```bash
scripts/setup-internal.sh --version 0.2.0-internal.1
```

The setup checks macOS, Java, Maven, GitHub authentication, and the three
provider CLIs. It backs up an existing `~/.m2/settings.xml`, adds an active
`subauth-github-packages` profile, stores the GitHub credential with file mode
`0600`, and downloads the requested starter and transitive modules.

For non-interactive setup, provide a classic PAT with `read:packages` without
putting it on the command line:

```bash
export SUBAUTH_GITHUB_TOKEN="..."
scripts/setup-internal.sh --yes --version 0.2.0-internal.1
unset SUBAUTH_GITHUB_TOKEN
```

The user must already have access to the private `kchanis1223/SubAuth`
repository. If the organization enforces SSO, authorize the token for that
organization as well.

After setup, consumer projects only add the starter dependency; the Maven
repository comes from the active settings profile:

```xml
<dependency>
    <groupId>io.github.kchanis1223</groupId>
    <artifactId>subauth-spring-boot-starter</artifactId>
    <version>0.2.0-internal.1</version>
</dependency>
```

CI in a consumer repository should configure the same repository and server
credentials using `actions/setup-java`, then grant that repository read access
to the package. Do not copy a developer's personal subscription credentials
into CI; SubAuth live provider calls remain a local macOS release gate.
