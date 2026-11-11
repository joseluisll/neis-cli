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

Interactive shell:

    java -jar target/neis-0.1.0-SNAPSHOT.jar

Run a single command non-interactively (Spring Shell 4):

    java -Dspring.shell.interactive.enabled=false -jar target/neis-0.1.0-SNAPSHOT.jar help

## Status

Backlog and requirements are tracked as GitHub issues in
`joseluisll/neis-cli`. Current work: US-01 CLI application skeleton
(epic #1).
