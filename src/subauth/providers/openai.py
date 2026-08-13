from subauth.providers.stub import StubProviderAdapter


def create_adapter() -> StubProviderAdapter:
    return StubProviderAdapter(
        "openai",
        "OpenAI capability probe and Codex App Server adapter are not implemented yet.",
    )

