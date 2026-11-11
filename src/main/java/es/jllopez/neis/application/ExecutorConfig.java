package es.jllopez.neis.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Assembly configuration: the asynchronous executor behind US-05.1
 * (issue #24). A small fixed pool: NEIS runs scraping/parsing work
 * that is mostly sequential per address, not a high-fanout workload.
 */
@Configuration
public class ExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public Executor taskExecutor() {
        return Executors.newFixedThreadPool(4);
    }
}
