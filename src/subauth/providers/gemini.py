from subauth.providers.stub import StubProviderAdapter


def create_adapter() -> StubProviderAdapter:
    return StubProviderAdapter(
        "gemini",
        "Gemini capability probe and Antigravity adapter are not implemented yet.",
    )

