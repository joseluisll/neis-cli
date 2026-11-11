package es.jllopez.neis.domain.port;

import es.jllopez.neis.domain.model.SystemInfo;

/**
 * Port for runtime information about the application.
 *
 * <p>Owned by the domain; infrastructure provides a connector
 * (adapter) implementing it.</p>
 */
public interface RuntimeInfoPort {

    SystemInfo loadSystemInfo();
}
