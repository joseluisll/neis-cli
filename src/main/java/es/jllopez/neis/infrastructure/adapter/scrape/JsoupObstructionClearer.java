package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.ClearedContent;
import es.jllopez.neis.domain.port.ObstructionClearingPort;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Connector (driven adapter) implementing the obstruction-clearing
 * port with jsoup (US-06.7, issue #75): removes popups, cookie
 * banners, modal overlays and advertisement nodes from the captured
 * HTML5 BEFORE it is stored or passed to other tasks, so extraction
 * and the stored capture refer to the main content of the page.
 *
 * <p>Two layers of rules, both deterministic:</p>
 * <ol>
 *   <li><b>Generic removal rules</b> — conservative, HTML-semantics
 *   based: declared popups/modals/dialog overlays ({@code <dialog>}
 *   open, {@code role=dialog}/{@code role=alertdialog} overlays), the
 *   common consent/cookie banner vocabulary, and the standard
 *   advertisement containers. No site-specific knowledge is encoded;
 *   the vocabulary is the generic web-wide one (aria-label and class
 *   fragments such as "cookie", "consent", "advert", "modal",
 *   "popup", "overlay").</li>
 *   <li><b>Configured page-specific selectors</b> — the task's
 *   declared obstructing selectors (US-12 model, US-12.3 validated at
 *   configuration time), complementing the generic rules.</li>
 * </ol>
 *
 * <p><b>Main-content guard</b>: a rule never removes an element that
 * CONTAINS the page's main listing content. When a candidate overlay
 * wraps {@code <main>}, {@code [role=main]} or the body's dominant
 * content, the wrapper is kept and only its chrome children are
 * removed (an overlay wrapper is harmless once its obstructing
 * children are gone). The rule: a removal candidate that contains a
 * {@code main}/{@code [role=main]} element, more than one {@code
 * <article>}, or a {@code <form>} with a {@code <select>} is never
 * removed itself — its qualifying children are, so the obstructing
 * pieces die either way without risking the data.</p>
 */
@Component
public class JsoupObstructionClearer implements ObstructionClearingPort {

    /**
     * Declared overlay roles/elements removed outright: an OPEN
     * {@code <dialog>} and the dialog/alertdialog roles. A
     * language-selector or hidden container that merely carries the
     * role is still obstruction for a scrape (it is not the listing);
     * the main-content guard below protects real content wrappers.
     */
    private static final String DECLARED_OVERLAYS =
            "dialog[open], [role=dialog], [role=alertdialog]";

    /**
     * Generic consent/cookie banner vocabulary, matched on aria-label,
     * id and class fragments (case-insensitive). These are the
     * web-wide generic fragments — no site-specific knowledge.
     */
    private static final String[] BANNER_CLASS_FRAGMENTS = {
            "cookieconsent", "cookie-consent", "cookie-banner", "cookiebar",
            "cookie-bar", "cookie-policy", "cookielaw", "cookie-law",
            "consent-banner", "consent-manager", "consent-notice",
            "gdpr-banner", "gdpr-bar", "wt-cck--banner"};

    /** aria-label fragments that declare a consent banner. */
    private static final String[] BANNER_LABEL_FRAGMENTS = {
            "cookie", "consent", "we use cookies"};

    /**
     * Generic advertisement containers: the standard ad element and
     * the common id/class vocabulary.
     */
    private static final String AD_SELECTOR =
            "ins.adsbygoogle, [id^=google_ads], [id^=div-gpt-ad], "
                    + "[class*=advertisement], [class*=ad-banner], "
                    + "[class*=ad-container], [class*=advert-], "
                    + "[aria-label*=advertisement i]";

    @Override
    public ClearedContent clear(String html, List<String> configuredSelectors) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        doc.outputSettings()
                .prettyPrint(false)
                .charset(StandardCharsets.UTF_8);

        int removed = 0;
        List<String> rulesApplied = new ArrayList<>();

        // 1. Declared overlays (dialog/roles), guarded.
        int n = removeGuarded(doc, DECLARED_OVERLAYS);
        if (n > 0) {
            removed += n;
            rulesApplied.add("declared-overlays");
        }

        // 2. Consent/cookie banner vocabulary, guarded.
        n = removeBanners(doc);
        if (n > 0) {
            removed += n;
            rulesApplied.add("consent-banners");
        }

        // 3. Advertisement containers (unguarded: an ad container is
        //    never the listing content).
        n = removeMatches(doc, AD_SELECTOR);
        if (n > 0) {
            removed += n;
            rulesApplied.add("advertisements");
        }

        // 4. The configured page-specific selectors (US-12), each
        //    guarded by the same main-content rule.
        if (configuredSelectors != null) {
            for (String selector : configuredSelectors) {
                if (selector == null || selector.isBlank()) {
                    continue;
                }
                n = removeGuarded(doc, selector.trim());
                if (n > 0) {
                    removed += n;
                    rulesApplied.add(selector.trim());
                }
            }
        }

        String cleaned = doc.html();
        return new ClearedContent(cleaned,
                cleaned.getBytes(StandardCharsets.UTF_8).length,
                removed, rulesApplied);
    }

    /** Removes every match of the selector with NO guard. An element
     * already detached by an earlier match of the SAME sweep (a
     * selector matching an ancestor and its descendant) is not
     * counted — the removal record states logical removals, not
     * jsoup select() hits (review non-blocking finding). Detached is
     * tested with ownerDocument()==null: jsoup 1.24 keeps the parent
     * pointer on a removed subtree, so hasParent() stays true there. */
    private int removeMatches(Document doc, String selector) {
        int count = 0;
        for (Element el : doc.select(selector)) {
            if (el.ownerDocument() != null) {
                el.remove();
                count++;
            }
        }
        return count;
    }

    /**
     * Removes every match of the selector UNLESS the element carries
     * the page's main content (guard, class javadoc): the wrapper
     * stays; its obstructing children were removed by their own
     * matching rules or stay when they are the content. Detached
     * elements (an ancestor match already removed them) are skipped
     * and not counted.
     */
    private int removeGuarded(Document doc, String selector) {
        int count = 0;
        for (Element el : doc.select(selector)) {
            if (el.ownerDocument() == null || carriesMainContent(el)) {
                continue;
            }
            el.remove();
            count++;
        }
        return count;
    }

    /** The consent/cookie banner vocabulary, as one guarded sweep. */
    private int removeBanners(Document doc) {
        int count = 0;
        List<Element> matches = new ArrayList<>();
        for (String fragment : BANNER_CLASS_FRAGMENTS) {
            doc.select("[class*=" + fragment + " i], [id*=" + fragment + " i]")
                    .forEach(matches::add);
        }
        for (String label : BANNER_LABEL_FRAGMENTS) {
            doc.select("[aria-label*=" + label + " i]").forEach(matches::add);
        }
        for (Element el : matches) {
            // jsoup may hand back the same element via several
            // fragments; detached elements are already gone.
            if (el.hasParent() && !carriesMainContent(el)) {
                el.remove();
                count++;
            }
        }
        return count;
    }

    /**
     * The main-content guard (US-06.7 acceptance criterion: clearing
     * rules do not remove main content): an element that contains
     * {@code <main>}, {@code [role=main]}, more than one {@code
     * <article>}, or a form with selects (a filter widget) is the
     * page's content-bearing wrapper — kept; the obstructing elements
     * INSIDE it are removed by their own rules.
     */
    private boolean carriesMainContent(Element el) {
        return el.selectFirst("main, [role=main]") != null
                || el.select("article").size() > 1
                || el.selectFirst("form select") != null;
    }
}
