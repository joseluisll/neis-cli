# NEIS - News and Events Information System

CLI-oriented Java application to collect, store and serve news and events
from configured addresses (EU institutional sources among them).

Built with Spring Boot and Spring Shell on a hexagonal architecture:
the domain defines ports (interfaces), the application layer holds the
use cases, and the infrastructure layer provides connectors (adapters).

## Build

Requires JDK 21+ and Maven 3.9+.

    mvn package

## Run

The interactive shell is DISABLED by default (owner decision
2026-11-20): the CLI is one-shot — a bare invocation runs its command
(if any) and exits.

Run a single command:

    java -jar target/neis-0.1.0-SNAPSHOT.jar help

Opt into the interactive shell explicitly:

    java -jar target/neis-0.1.0-SNAPSHOT.jar --spring.shell.interactive.enabled=true

## Status

Backlog and requirements are tracked as GitHub issues in
`joseluisll/neis-cli`. Current work: US-01 CLI application skeleton
(epic #1).
