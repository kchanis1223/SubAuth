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

## Phase 1: macOS daemon foundation

- Python 3.12+
- per-user Unix domain socket
- macOS Keychain credential vault
- `launchd` installation and lazy startup
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
Anthropic. SubAuth will expose the technical mode but mark it experimental,
display a strong login warning, and recommend API authentication before a
formal release.

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

