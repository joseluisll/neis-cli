package es.jllopez.neis.domain.model;

/**
 * Basic identity and runtime information about the NEIS application.
 *
 * <p>Pure domain model: no framework or third-party dependencies.</p>
 */
public record SystemInfo(
        String name,
        String description,
        String version,
        String javaVersion) {
}
