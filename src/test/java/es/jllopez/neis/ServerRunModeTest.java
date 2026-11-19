package es.jllopez.neis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SERVER run mode gate (US-18.6, issue #121): by default the app
 * runs with NO web layer (one-shot CLI invocations must behave and
 * exit exactly as before — a started Tomcat would hold the JVM alive),
 * and the mode combination is validated fail-closed at boot.
 */
class ServerRunModeTest {

    @TempDir
    Path tempDir;

    /**
     * The packaged DEFAULTS live in application.yml (the packaged jar's
     * behavior), so pin the file: web layer OFF and the adapter gate
     * OFF unless server.sh (or an explicit override) says otherwise.
     */
    @Test
    @SuppressWarnings("unchecked")
    void thePackagedDefaultsRunWithoutAWebLayer() {
        Map<String, Object> yml = new Yaml().load(
                getClass().getResourceAsStream("/application.yml"));
        Map<String, Object> spring = (Map<String, Object>) yml.get("spring");
        Map<String, Object> main = (Map<String, Object>) spring.get("main");
        Map<String, Object> neis = (Map<String, Object>) yml.get("neis");
        Map<String, Object> server = (Map<String, Object>) neis.get("server");
        assertThat(main.get("web-application-type"))
                .as("the default boot must be non-web (CLI one-shot exits)")
                .isEqualTo("none");
        assertThat(server.get("enabled"))
                .as("the remote adapters are gated OFF by default")
                .isEqualTo(false);
    }

    @Test
    void aNonWebContextStartsNoWebServer() {
        new ApplicationContextRunner()
                .withUserConfiguration(NeisApplication.class)
                .withPropertyValues(
                        "spring.main.web-application-type=none",
                        "spring.datasource.url=jdbc:sqlite:"
                                + tempDir.resolve("servermode.db"),
                        "spring.shell.interactive.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // the web server auto-configuration backed off entirely
                    assertThat(context).doesNotHaveBean(ServletWebServerFactory.class);
                });
    }

    @Test
    void adaptersEnabledWithoutAServletWebLayerFailsTheBoot() {
        new ApplicationContextRunner()
                .withUserConfiguration(NeisApplication.class)
                .withPropertyValues(
                        "neis.server.enabled=true",
                        "spring.main.web-application-type=none",
                        "spring.datasource.url=jdbc:sqlite:"
                                + tempDir.resolve("servermode-invalid.db"),
                        "spring.shell.interactive.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // config validation surfaces WRAPPED — walk the cause
                    // chain to our message (the binder-exception lesson)
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).isNotNull();
                    Throwable cause = failure;
                    boolean found = false;
                    while (cause != null && !found) {
                        found = String.valueOf(cause.getMessage())
                                .contains("neis.server.enabled=true requires the web layer");
                        cause = cause.getCause();
                    }
                    assertThat(found).as("boot failure names the property pair fix")
                            .isTrue();
                });
    }

    @Test
    void theModeCombinationInvariantIsPure() {
        assertThat(ServerModeConfig.validate(true, "none")).isPresent();
        assertThat(ServerModeConfig.validate(true, "reactive")).isPresent();
        assertThat(ServerModeConfig.validate(false, "none")).isEmpty();
        assertThat(ServerModeConfig.validate(false, "servlet")).isEmpty();
        // the server.sh combination is the legal enabled shape
        assertThat(ServerModeConfig.validate(true, "servlet")).isEmpty();
    }

    @Test
    void theBindCustomizerAppliesBindAndPortInServerModeOnly() {
        // Boot 4.1's stock customizer no longer maps server.address
        // (found live: the server bound *:8080 with the property set),
        // so OUR customizer is the applier — pin both properties.
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

        MockEnvironment off = new MockEnvironment();
        new ServerModeConfig(off).serverBindCustomizer(off)
                .customize(factory);
        assertThat(factory.getAddress()).isNull();

        MockEnvironment on = new MockEnvironment();
        on.setProperty("neis.server.enabled", "true");
        on.setProperty("neis.server.bind", "127.0.0.1");
        on.setProperty("neis.server.port", "8099");
        new ServerModeConfig(on).serverBindCustomizer(on)
                .customize(factory);
        assertThat(factory.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
        assertThat(factory.getPort()).isEqualTo(8099);
    }
}
