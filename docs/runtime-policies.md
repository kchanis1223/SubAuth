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

제3자를 위한 서비스에 구독 모델을 사용하는 것은 약관상 권장되지 않습니다.
Gemini 어댑터를 사용하기 전에 Google Antigravity의 최신 약관과 사용 범위를 직접
확인해야 합니다.

The adapter removes Gemini API, ADC, Vertex, and Cloud-project variables. It
also rejects Antigravity configuration that enables purchased AI-credit
fallback after subscription allowance is exhausted.
