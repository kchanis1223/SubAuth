# SubAuth

SubAuth is a local sidecar daemon that lets an AI service under development use
the developer's own AI subscriptions. The first providers are OpenAI, Claude,
and Gemini.

The main service sends provider-neutral requests to SubAuth. SubAuth owns the
developer's credentials, selects an official runtime or a direct subscription
adapter, and returns normalized streaming events.

```text
main service -> SubAuth -> provider runtime or protocol
main service <- SubAuth <- provider runtime or protocol
```

## Project status

The repository currently contains the Python daemon foundation and provider
contracts. Provider authentication and live inference are intentionally not yet
implemented; they follow the capability-probe phase described in
[`docs/implementation-plan.md`](docs/implementation-plan.md).

## Development

Python 3.12 or newer is required. The current foundation uses only the standard
library, so it can be exercised without installing project dependencies.

```bash
python3 -m unittest discover -s tests -v
PYTHONPATH=src python3 -m subauth serve
```

In another terminal:

```bash
PYTHONPATH=src python3 -m subauth status
PYTHONPATH=src python3 -m subauth providers
```

The daemon listens on a per-user Unix domain socket by default. It does not
perform end-user access control; the calling main service owns that concern.

