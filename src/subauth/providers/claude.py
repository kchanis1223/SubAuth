from subauth.providers.stub import StubProviderAdapter


def create_adapter() -> StubProviderAdapter:
    return StubProviderAdapter(
        "claude",
        "Claude capability probe and subscription adapter are not implemented yet.",
    )

