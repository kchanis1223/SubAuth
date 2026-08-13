from __future__ import annotations

import asyncio

from subauth import AsyncSubAuth


async def main() -> None:
    client = AsyncSubAuth()
    result = await client.responses.create(
        provider="openai",
        input="Reply exactly: SDK_OK",
        system="Answer concisely.",
    )
    print(result.text)

    stream = client.responses.stream(
        provider="openai",
        input="Reply exactly: STREAM_OK",
    )
    async with stream:
        async for event in stream:
            if event.type == "output.text.delta":
                print(event.data.get("delta", ""), end="", flush=True)
    print()


if __name__ == "__main__":
    asyncio.run(main())
