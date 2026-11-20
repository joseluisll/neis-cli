package es.jllopez.neis.domain.model;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Item-relevance validation for the news/events repository
 * (US-13.9, issue #136): promotional and utility widgets rendered in
 * the same listing container as real articles — subscription prompts,
 * quizzes, campaign hubs, section teasers, cookie/legal links — must
 * not enter the repository as items.
 *
 * <p>HEURISTIC-ONLY by design decision (implementation review
 * 2026-11-20, recorded on #136): the classifier runs on every
 * extracted item at storage time, so AI-assisted classification would
 * add a model call per item on a path that must also work with AI
 * disabled (the installed service runs with AI off), and the live
 * evidence (ADDR-010: quiz widget, subscription card, campaign hub,
 * section teaser stored as news) is separable by high-precision
 * lexical rules. An AI-assisted tier remains a future refinement and
 * would carry the [AI] decision-point logging when it lands.</p>
 *
 * <p>Shared by the real store connector AND the in-memory test double
 * so the filter cannot drift between them (the US-14.9 pattern). The
 * classifier validates ITEMS, never the page selector — the existing
 * FK/selector mechanics are unchanged. Every exclusion is a decided,
 * logged event (rule + title), never a silent drop.</p>
 */
public final class NewsItemRelevance {

    /** One exclusion: the rule that matched and the matched fragment
     * (the title/URL text that triggered it). */
    public record Exclusion(String rule, String detail) {}

    private record Rule(String name, Pattern pattern) {}

    /** Cookie banners, legal and navigation-utility links. */
    private static final Rule UTILITY = new Rule("utility-page",
            Pattern.compile("pol[ií]tica de privacidad|aviso legal|"
                    + "t[eé]rminos y condiciones|pol[ií]tica de cookies|"
                    + "configuraci[oó]n de cookies|qui[eé]nes somos|"
                    + "contacto|sitemap|mapa web|"
                    + "privacy policy|terms (of|and) (service|use)|"
                    + "cookie (settings|policy)|about us",
                    Pattern.CASE_INSENSITIVE));

    /** Subscription and newsletter prompts ('Newsletter especial —
     * Apúntate' was stored as news on ADDR-010). */
    private static final Rule SUBSCRIPTION = new Rule("subscription",
            Pattern.compile("newsletter|suscr[ií]bete|suscripci[oó]n|"
                    + "ap[uú]ntate|reg[ií]strate|recibe cada|"
                    + "subscribe|sign up (for|to)|registration",
                    Pattern.CASE_INSENSITIVE));

    /** Quizzes and interactive widgets. Deliberately NARROW: generic
     * words ('encuesta', 'vota', 'poll') appear in real poll-coverage
     * headlines ('Cómo van las encuestas del 29N' is news, not a
     * widget) — only unambiguous widget markers qualify. The ADDR-010
     * quiz card is caught by its URL marker; a widget whose title AND
     * url carry no marker is a known heuristic limitation (the
     * AI-assisted tier would refine it). */
    private static final Rule INTERACTIVE = new Rule("interactive-widget",
            Pattern.compile("\\bquiz\\b|\\bquizzes\\b|\\btrivia\\b|"
                    + "juega con|\\btests?\\b",
                    Pattern.CASE_INSENSITIVE));

    /** Section/campaign hubs and teasers ('Toda la información de la
     * campaña' — a campaign-hub link stored as news). */
    private static final Rule HUB = new Rule("section-hub",
            Pattern.compile("toda (la|las) (informaci[oó]n|noticias|"
                    + "actualidad)|todas las noticias|lo mejor de|"
                    + "todo sobre|especial (elecciones|campa[nñ]a)|"
                    + "sigue (la|el) (directo|en directo)|"
                    + "cobertura (completa|especial)",
                    Pattern.CASE_INSENSITIVE));

    /** Hub-shaped URL paths — the item URL landing on a container, not
     * an article page. Kept narrow: only unambiguous container paths. */
    private static final Rule HUB_URL = new Rule("hub-url",
            Pattern.compile("/(tags?|etiquetas?|temas|category|"
                    + "categories|secciones?|sections?|portada)(/|$|\\?)",
                    Pattern.CASE_INSENSITIVE));

    private static final List<Rule> RULES =
            List.of(UTILITY, SUBSCRIPTION, INTERACTIVE, HUB, HUB_URL);

    private NewsItemRelevance() {
    }

    /**
     * The exclusion for this item — the first matching rule, or null
     * when the item passes relevance validation (the common case:
     * null means KEEP, never a silent decision). Both the title and
     * the URL are examined; the matched fragment is returned in the
     * exclusion so the log line names the evidence.
     */
    public static Exclusion exclusionOf(NewsEventItem item) {
        if (item == null) {
            return null;
        }
        String title = item.title() == null ? "" : item.title();
        String url = item.url() == null ? "" : item.url();
        for (Rule rule : RULES) {
            var tm = rule.pattern().matcher(title);
            if (tm.find()) {
                return new Exclusion(rule.name(), "title: '" + tm.group() + "'");
            }
            var um = rule.pattern().matcher(url);
            if (um.find()) {
                return new Exclusion(rule.name(), "url: '" + um.group() + "'");
            }
        }
        return null;
    }
}
