package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.SystemInfo;
import es.jllopez.neis.domain.port.RuntimeInfoPort;
import org.springframework.stereotype.Service;

/**
 * Use case: report the identity and runtime information of NEIS.
 *
 * <p>Depends only on domain ports, never on infrastructure
 * implementations.</p>
 */
@Service
public class SystemInfoService {

    private final RuntimeInfoPort runtimeInfoPort;

    public SystemInfoService(RuntimeInfoPort runtimeInfoPort) {
        this.runtimeInfoPort = runtimeInfoPort;
    }

    public SystemInfo getSystemInfo() {
        return runtimeInfoPort.loadSystemInfo();
    }
}
