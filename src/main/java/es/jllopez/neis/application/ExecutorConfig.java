package es.jllopez.neis.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Assembly configuration: the asynchronous executor behind US-05.1
 * (issue #24). A small fixed pool: NEIS runs scraping/parsing work
 * that is mostly sequential per address, not a high-fanout workload.
 *
 * <p>The workers are DAEMON threads. A plain fixed pool parks idle,
 * non-daemon workers forever, and the Spring destroy method only runs
 * when the JVM shutdown begins — but the JVM waits for the workers
 * before beginning it. Any CLI run that used the executor (a chain
 * run, an 'execution run') then never exits: the command finishes,
 * main returns, and the process hangs with the idle worker. Daemon
 * threads let the JVM exit once main is done; the Spring-managed
 * shutdown still runs on the context-close path.</p>
 */
@Configuration
public class ExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public Executor taskExecutor() {
        return Executors.newFixedThreadPool(4, runnable -> {
            Thread worker = new Thread(runnable, "neis-executor");
            worker.setDaemon(true);
            return worker;
        });
    }
}
