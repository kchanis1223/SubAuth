# Runtime policies

## OpenAI

The OpenAI adapter uses Codex App Server's supported ChatGPT login and refuses
non-ChatGPT account types for subscription inference. It does not read or
export raw OpenAI access or refresh tokens.

## Claude

Anthropic does not permit third-party products to route user requests through
consumer Claude subscription credentials. The Claude adapter exists for local
development and limited previews, is marked experimental in response metadata,
and must be replaced with supported API authentication before formal release.

SubAuth removes direct Anthropic API and supported cloud-provider credentials
from the Claude Code child environment so the development transport cannot
silently consume usage-billed API traffic.

## Gemini / Antigravity

Google Antigravity terms restrict third-party software access. The Gemini
adapter is terms-restricted and limited to developer-controlled evaluation.
It must not be used for production traffic without Google authorization.

The adapter removes Gemini API, ADC, Vertex, and Cloud-project variables. It
also rejects Antigravity configuration that enables purchased AI-credit
fallback after subscription allowance is exhausted.
