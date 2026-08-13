# Implementation plan

## Phase 0: capability probes

Measure each provider before implementing common routing:

- login and refresh
- text and streaming
- image and file input
- provider-hosted web search
- provider-hosted code execution
- function tool calls
- multi-turn resume
- cancellation
- model and subscription-limit discovery

Record results separately for official runtime and direct subscription
transports. A provider implementation begins only after its probe fixtures are
captured with all credentials redacted.

### Current progress

- OpenAI Codex App Server lifecycle: implemented
- OpenAI managed ChatGPT account probe: implemented
- OpenAI model and text/image capability discovery: implemented
- OpenAI managed browser-login bootstrap: implemented
- OpenAI subscription model inference: implemented
- OpenAI normalized Unix-socket streaming: implemented
- OpenAI tool-surface suppression audit: pending
- Claude Code authentication and subscription probe: implemented
- Claude Code normal login and `setup-token` detection: implemented
- Claude normalized Unix-socket text streaming: implemented
- Claude filesystem/tool/MCP suppression: implemented for CLI flags; adversarial audit pending
- Gemini legacy CLI consumer transition analysis: completed
- Antigravity CLI authentication and model probe: live verified
- Antigravity normalized Unix-socket streaming: live verified
- Gemini API/Vertex environment fallback prevention: implemented
- Antigravity AI-credit fallback refusal: implemented
- Antigravity tool-step termination: implemented; pre-execution suppression unavailable
- macOS per-user LaunchAgent installation and lifecycle: implemented
- client-triggered lazy daemon startup: implemented

## Phase 1: macOS daemon foundation

- Python 3.12+
- per-user Unix domain socket
- macOS Keychain credential vault
- `launchd` installation and lazy startup (implemented)
- provider worker lifecycle
- token-redacted structured logs
- protocol version negotiation

## Phase 2: provider order

1. OpenAI: Codex App Server, then direct subscription, then API.
2. Claude: official runtime, then `setup-token`-derived experimental transport,
   then API.
3. Gemini: Antigravity runtime, then experimental subscription transport, then
   Gemini API or Google Cloud.

Claude product routing through subscription credentials is not endorsed by
Anthropic. SubAuth exposes the technical mode but marks it experimental,
includes a strong warning in probe and inference events, and recommends API
authentication before a formal release.

Google Antigravity officially documents headless scripting, but its additional
terms restrict third-party software from accessing the service. The SubAuth
wrapper is therefore terms-restricted, limited to developer-controlled
evaluation, and must not be used for external-user or production traffic
without Google authorization.

## Phase 3: common response API

- text streaming
- multimodal input
- files
- hosted web search and code execution
- main-service function-tool round trips
- sessions and cancellation
- structured output
- optional OpenAI-compatible HTTP shim

## Phase 4: API transition

Run the same conformance suite against subscription and API transports. Report
behavioral differences through capability metadata instead of implying exact
runtime equivalence.

## Foundation acceptance criteria

- no OpenCode dependency
- daemon round-trip over a private Unix socket
- registered OpenAI, Claude, and Gemini adapter contracts
- stable versioned protocol framing
- standard-library unit tests pass on Python 3.12+
- no provider token exposed to the client contract
