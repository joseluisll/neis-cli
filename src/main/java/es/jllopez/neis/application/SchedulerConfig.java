package es.jllopez.neis.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Assembly configuration: the task scheduler behind US-05.2 (issue
 * #25). Schedules are registered per config entry as cron triggers and
 * fire the asynchronous executor — the scheduling itself never blocks
 * the user interfaces.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ScheduleProperties.class)
public class SchedulerConfig {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("neis-scheduler-");
        return scheduler;
    }
}
