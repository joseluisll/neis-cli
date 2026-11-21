package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.NewsEventItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * US-06.16 (issue #148, owner decision 2026-11-20 — disposition of
 * #147): an address's listing yields items from the address's OWN
 * host. Any extracted item whose resolved URL host differs from the
 * registered address's URL host is dropped at extraction — strictly
 * same-host, no allow-list, no policy knob. The accepted consequence:
 * sister-brand syndication (e.g. mujerhoy.com cards on an abc.es
 * portada) is dropped exactly like the external partner widgets
 * (cope.es); same-host sponsored cards (abc.es/contentfactory) stay.
 *
 * <p>The filter lives ONCE on the application extraction path and is
 * applied by every consumer of the extraction (the pipeline's plain,
 * paginated and filtered-application sites, the standalone WEBSCRAPE
 * step and the FEED parse), so no connector or retrieval strategy can
 * diverge — static fixtures, live HTTP, rendered and cached serves
 * share it by construction. It runs at CAPTURE time: stored rows are
 * never retroactively dropped or re-judged.</p>
 *
 * <p>Host comparison is on the HOST component only (case-insensitive,
 * no path, scheme or port beyond an explicit {@code :80}/{@code :443}
 * being the scheme defaults). An item with NO resolvable URL cannot be
 * host-judged: it stays and follows the US-14.4 incomplete flow as
 * today. Every dropped item logs ONE operational line; the caller
 * carries the dropped count into the run outcome.</p>
 */
public final class SameHostFilter {

    private static final Logger log =
            LoggerFactory.getLogger(SameHostFilter.class);

    private SameHostFilter() {
    }

    /** The kept items and how many external ones were dropped. */
    public record Result(List<NewsEventItem> kept, int dropped) {
        /** The run-outcome fragment; empty when nothing was dropped. */
        public String note() {
            return dropped == 0 ? ""
                    : "; " + dropped + " external item(s) dropped (host mismatch)";
        }
    }

    public static Result apply(Address address, List<NewsEventItem> items) {
        String addressHost = hostOf(address.url());
        if (addressHost == null) {
            // An unparseable registered URL cannot ground the rule —
            // keep everything (defensive; US-04.1 validates at registration).
            return new Result(List.copyOf(items), 0);
        }
        List<NewsEventItem> kept = new ArrayList<>();
        int dropped = 0;
        for (NewsEventItem item : items) {
            String itemHost = item.url() == null || item.url().isBlank()
                    ? null : hostOf(item.url());
            if (itemHost != null && !itemHost.equalsIgnoreCase(addressHost)) {
                dropped++;
                log.info("WEBSCRAPE {}: external item dropped (host mismatch) — "
                                + "item host '{}' vs address host '{}': {}",
                        address.id(), itemHost, addressHost,
                        item.title() == null || item.title().isBlank()
                                ? item.url() : item.title());
                continue;
            }
            // same host, or no resolvable URL (US-14.4 incomplete flow —
            // a URL-less item is never host-judged)
            kept.add(item);
        }
        return new Result(List.copyOf(kept), dropped);
    }

    /** The lowercased host of an absolute URL, or null when the URL
     * carries no resolvable host (relative, opaque or malformed —
     * such a value cannot be host-judged). */
    static String hostOf(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
