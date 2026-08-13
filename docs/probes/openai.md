# OpenAI official-runtime probe

Verified on 2026-08-13 with Codex CLI 0.145.0 on macOS.

## Runtime path

```text
SubAuth daemon -> persistent Codex App Server -> ChatGPT subscription
```

SubAuth starts `codex app-server --stdio`, initializes its newline-delimited
JSON-RPC protocol, and lets Codex own the managed ChatGPT OAuth lifecycle.
SubAuth never reads Codex's access or refresh token files.
It also refuses to run the subscription transport when `account/read` reports
API-key authentication, preventing an accidental usage-billed fallback.

The adapter currently uses these stable methods and notifications:

- `initialize` and `initialized`
- `account/read`
- `account/login/start`
- `model/list`
- `thread/start`
- `turn/start`
- `item/agentMessage/delta`
- `error`
- `turn/completed`

Official reference: <https://developers.openai.com/codex/app-server>

## Verified capabilities

| Capability | Result |
| --- | --- |
| Managed ChatGPT login | Working |
| Subscription plan detection | Working |
| Model catalog | Working |
| Text input | Working |
| Image modality discovery | Working; inference probe pending |
| Text delta streaming | Working |
| Ephemeral thread | Working |
| Read-only sandbox | Working |
| Session continuation | Runtime supports it; intentionally not exposed by stateless SubAuth |

The live smoke request produced `SUBAUTH_OK` as three normalized text deltas and
finished with `response.completed`.

## Stable protocol constraint

The installed App Server rejects `thread/start.environments` and
`thread/start.dynamicTools` unless the client opts into `experimentalApi`.
SubAuth deliberately uses the stable protocol and omits those fields. It still
sets an ephemeral thread, read-only sandbox, and `approvalPolicy: never`.

This means V1 does not yet claim that every built-in Codex tool is absent. It
claims that the smoke runtime cannot write through the sandbox or request a
permission escalation. A later capability pass must explicitly verify tool
surface suppression before untrusted prompts are accepted.
