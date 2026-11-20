package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.ClearedContent;
import es.jllopez.neis.domain.port.ObstructionClearingPort;
import es.jllopez.neis.application.ConsentProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p><b>Main-content guard</b>: a GENERIC rule never removes an
 * element that CONTAINS the page's main listing content. When a
 * candidate overlay wraps {@code <main>}, {@code [role=main]} or the
 * body's dominant content, the wrapper is kept and only its chrome
 * children are removed (an overlay wrapper is harmless once its
 * obstructing children are gone). The rule: a removal candidate that
 * contains a {@code main}/{@code [role=main]} element, more than one
 * {@code <article>}, or a {@code <form>} with a {@code <select>} is
 * never removed by the generic sweeps — its qualifying children are,
 * so the obstructing pieces die either way without risking the data.
 * The CONFIGURED page-specific selectors are the operator's explicit
 * override and bypass the guard (review non-blocking finding 2: a
 * modal with a select survived its own configured selector
 * otherwise).</p>
 */
@Component
public class JsoupObstructionClearer implements ObstructionClearingPort {

    private static final Logger log = LoggerFactory.getLogger(
            JsoupObstructionClearer.class);

    /**
     * US-06.13 (issue #137): the CONSENT markers that classify a
     * removed overlay as a CONSENT dialog (cookie/data-use acceptance)
     * rather than another dismissible modal — matched case-insensitively
     * on the element's id, class and aria-label. The consent/cookie
     * banner vocabulary sweep below is consent by definition; this
     * check classifies DECLARED overlays (role=dialog etc.), which may
     * be either a consent layer or another modal.
     */
    private static final String[] CONSENT_MARKERS =
            {"cookie", "consent", "gdpr"};

    /**
     * The US-06.13 consent policy (configuration, not hardcode):
     * ACCEPT or REJECT, default REJECT (ConsentProperties fails the
     * boot on an unknown value). The jsoup pipeline is
     * non-interactive — under BOTH policies the dialog markup is
     * DISMISSED (removed from the capture, never mixed into the page
     * content); the policy is the operator's RECORDED data-use stance,
     * logged per encounter and stored on the capture's clearing
     * record. NOTE: the no-arg constructor binds the DEFAULT policy
     * (tests) — Spring uses the @Autowired one below.
     */
    private final ConsentProperties consentPolicy;

    @Autowired
    public JsoupObstructionClearer(ConsentProperties consentPolicy) {
        this.consentPolicy = consentPolicy;
    }

    public JsoupObstructionClearer() {
        this(new ConsentProperties(null));
    }

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
     * the common id/class vocabulary. The open class fragment
     * {@code [class*=advert-]} was NARROWED AWAY (US-06.7 review
     * non-blocking finding 1): a class merely containing "advert-"
     * (e.g. a site's own {@code advert-results-list}) is not
     * necessarily an ad container, and an unguarded removal there
     * silently deletes listing content — the rule now keeps only the
     * unambiguous standard forms (advertisement/ad-banner/
     * ad-container) and the structural ad ids/elements; anything
     * beyond them is the configured page-specific selectors' job
     * (US-12).
     */
    private static final String AD_SELECTOR =
            "ins.adsbygoogle, [id^=google_ads], [id^=div-gpt-ad], "
                    + "[class*=advertisement], [class*=ad-banner], "
                    + "[class*=ad-container], [aria-label*=advertisement i]";

    @Override
    public ClearedContent clear(String html, List<String> configuredSelectors) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        doc.outputSettings()
                .prettyPrint(false)
                .charset(StandardCharsets.UTF_8);

        int removed = 0;
        List<String> rulesApplied = new ArrayList<>();
        // US-06.13 (issue #137): the classified dialog counts —
        // consent dialogs (cookie/data-use), other dismissible modals
        // (newsletter/registration walls), plain obstructing elements
        // (ads, configured selectors — the pre-existing path).
        int consentCount = 0;
        int modalCount = 0;

        // 1. Declared overlays (dialog/roles), guarded. Each removed
        //    overlay is CLASSIFIED: a consent marker on the element
        //    (cookie/consent/gdpr) makes it a CONSENT dialog, otherwise
        //    it is another dismissible modal.
        var declared = doc.select(DECLARED_OVERLAYS).stream()
                .filter(el -> el.ownerDocument() != null)
                .filter(el -> !carriesMainContent(el))
                .toList();
        for (Element el : declared) {
            el.remove();
            removed++;
            if (isConsentOverlay(el)) {
                consentCount++;
            } else {
                modalCount++;
            }
        }
        if (!declared.isEmpty()) {
            rulesApplied.add("declared-overlays");
        }

        // 2. Consent/cookie banner vocabulary, guarded — consent by
        //    definition.
        int banners = removeBanners(doc);
        if (banners > 0) {
            removed += banners;
            consentCount += banners;
            rulesApplied.add("consent-banners");
        }

        // 3. Advertisement containers (unguarded: an ad container is
        //    never the listing content) — plain obstructing elements.
        int n = removeMatches(doc, AD_SELECTOR);
        if (n > 0) {
            removed += n;
            rulesApplied.add("advertisements");
        }

        // 4. The configured page-specific selectors (US-12), applied
        //    WITHOUT the main-content guard (US-06.7 review
        //    non-blocking finding 2): the operator explicitly aimed
        //    the selector at that element (US-12.3 validated it at
        //    configuration time) — an implicit guard second-guessing
        //    the configuration made the target unremovable (a
        //    signup modal with a filter-like select survived its own
        //    configured selector). The configured selector is the
        //    override; the guard protects only the generic rules.
        if (configuredSelectors != null) {
            for (String selector : configuredSelectors) {
                if (selector == null || selector.isBlank()) {
                    continue;
                }
                n = removeMatches(doc, selector.trim());
                if (n > 0) {
                    removed += n;
                    rulesApplied.add(selector.trim());
                }
            }
        }

        // US-06.13: every dialog encounter produces an operational log
        // line (type, action taken, element count), and the classified
        // counts ride on the clearing record into the capture metadata
        // (consent policy recorded, never hardcoded).
        List<ClearedContent.DialogEncounter> dialogs = new ArrayList<>();
        if (consentCount > 0) {
            String action = "dismissed (removed from the capture); consent "
                    + "policy " + consentPolicy.policy() + " recorded";
            log.info("[Consent] consent dialog(s) detected: {} element(s) "
                            + "— policy {}: dismissed, preference recorded",
                    consentCount, consentPolicy.policy());
            dialogs.add(new ClearedContent.DialogEncounter(
                    "consent", consentCount, action));
        }
        if (modalCount > 0) {
            log.info("[Clearing] dismissible modal(s): {} element(s) "
                    + "— dismissed (removed from the capture)", modalCount);
            dialogs.add(new ClearedContent.DialogEncounter(
                    "modal", modalCount,
                    "dismissed (removed from the capture)"));
        }

        String cleaned = doc.html();
        return new ClearedContent(cleaned,
                cleaned.getBytes(StandardCharsets.UTF_8).length,
                removed, rulesApplied, dialogs);
    }

    /** Whether a removed declared overlay is a CONSENT dialog (US-06.13):
     * a cookie/consent/gdpr marker on its id, class or aria-label. */
    private boolean isConsentOverlay(Element el) {
        String haystack = (el.id() + " " + el.className() + " "
                + el.attr("aria-label")).toLowerCase(java.util.Locale.ROOT);
        for (String marker : CONSENT_MARKERS) {
            if (haystack.contains(marker)) {
                return true;
            }
        }
        return false;
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
            // fragments; detached elements are already gone. Detached
            // is tested with ownerDocument()==null (the removeBanners
            // counterpart of the removeMatches/removeGuarded rule):
            // jsoup 1.24 keeps the parent pointer on a removed subtree,
            // so a banner INSIDE an already-removed banner still reports
            // hasParent()==true — hasParent() would count it (review
            // round-2 non-blocking finding).
            if (el.ownerDocument() != null && !carriesMainContent(el)) {
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
