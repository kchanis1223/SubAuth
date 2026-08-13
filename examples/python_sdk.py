from __future__ import annotations

import asyncio

from subauth import AsyncSubAuth


async def main() -> None:
    client = AsyncSubAuth()
    session = await client.sessions.create(
        provider="openai",
        model="auto",
        system="Answer concisely.",
    )

    first = await client.responses.create(
        provider="openai",
        input="Remember the number 17.",
        session=session,
    )
    print(first.text)

    stream = client.responses.stream(
        provider="openai",
        input="What number did I ask you to remember?",
        session=session,
    )
    async with stream:
        async for event in stream:
            if event.type == "output.text.delta":
                print(event.data.get("delta", ""), end="", flush=True)
    print()


if __name__ == "__main__":
    asyncio.run(main())
