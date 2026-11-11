# NEIS Architecture

NEIS is a CLI application organized as a hexagonal architecture
(ports and adapters). Dependencies point inwards only:

```
                    ┌─────────────────────────────────────────┐
                    │             infrastructure              │
                    │  ┌─────────┐  ┌─────────┐  ┌─────────┐  │
   CLI in ────────▶ │  │ shell   │  │ system  │  │   ai    │  │
                    │  │ adapter │  │ adapter │  │ adapter │  │
                    │  └────┬────┘  └────┬────┘  └────┬────┘  │
                    └───────┼────────────┼────────────┼───────┘
                            ▼            ▼            ▼
                    ┌─────────────────────────────────────────┐
                    │              application                │
                    │        (use cases, orchestration)       │
                    └───────────────────────┬─────────────────┘
                                            ▼
                    ┌─────────────────────────────────────────┐
                    │                 domain                  │
                    │     (model + ports, pure Java)          │
                    └─────────────────────────────────────────┘
```

## Layers

- **domain** (`es.jllopez.neis.domain`): the heart. Pure Java — no Spring,
  no third-party libraries. Contains the model and the **ports**:
  interfaces the domain owns and the outside world must implement.
- **application** (`es.jllopez.neis.application`): use cases. Depends only
  on domain ports. May use Spring annotations for wiring (`@Service`),
  but no infrastructure.
- **infrastructure** (`es.jllopez.neis.infrastructure`): **connectors**
  (adapters). Driving adapters (e.g. Spring Shell commands) invoke
  application use cases; driven adapters implement domain ports
  (e.g. runtime info from build properties, AI via Spring AI, retrieval,
  persistence). This is the only layer allowed to touch third-party
  libraries and frameworks.

## Ports so far

| Port | Direction | Connector |
|---|---|---|
| `RuntimeInfoPort` | driven | `MavenRuntimeInfoConnector` (Spring Boot build properties) |
| `AiAssistantPort` | driven | `SpringAiConnector` (Spring AI `ChatClient`, provider-neutral) |

## Enforced rules (ArchUnit)

- `domain` may not depend on `application` or `infrastructure`.
- `application` may not depend on `infrastructure`.
- `domain` must be free of Spring imports.
- `domain` and `application` must not import the open-source building
  blocks listed in `docs/TECH-STACK.md` (jsoup, HttpClient 5, Playwright,
  Tika, Hibernate/JPA, Spring AI, Rome, Readability4J, sqlite) — these
  are implementation details behind the ports (issue #63).

## Conventions

- New functionality = a new port in `domain.port` (if the outside world
  must provide something) plus its connectors in
  `infrastructure.adapter`, wired through an application use case.
- CLI commands live in `infrastructure.adapter.shell` and stay thin.
