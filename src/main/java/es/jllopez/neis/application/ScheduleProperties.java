package es.jllopez.neis.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configurable schedules (US-05.2, issue #25): which tasks run, over
 * which addresses, and when — defined in configuration (application
 *.yml), never hard-coded, changeable without code modifications.
 *
 * <pre>
 * neis:
 *   schedule:
 *     entries:
 *       - name: hourly-news-scrape
 *         task: WEBSCRAPE
 *         address: ADDR-001
 *         cron: "0 5 * * * *"
 * </pre>
 */
@ConfigurationProperties(prefix = "neis.schedule")
public record ScheduleProperties(List<Entry> entries) {

    public ScheduleProperties {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** One configured schedule entry. */
    public record Entry(String name, String task, String address, String cron) {
    }
}
