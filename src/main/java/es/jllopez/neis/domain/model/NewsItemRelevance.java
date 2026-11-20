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

    /** Cookie banners, legal and navigation-utility links. Kept to
     * multi-word legal/cookie phrases — single generic words were
     * rejected by review (bare 'contacto' excluded real headlines like
     * 'Primer contacto entre el Gobierno y Puigdemont'). */
    private static final Rule UTILITY = new Rule("utility-page",
            Pattern.compile("pol[ií]tica de privacidad|aviso legal|"
                    + "t[eé]rminos y condiciones|pol[ií]tica de cookies|"
                    + "configuraci[oó]n de cookies|qui[eé]nes somos|"
                    + "mapa web|"
                    + "privacy policy|terms (of|and) (service|use)|"
                    + "cookie (settings|policy)",
                    Pattern.CASE_INSENSITIVE));

    /** Subscription/newsletter prompts. IMPERATIVE CTA FORMS ONLY —
     * bare 'newsletter'/'suscripción'/'registration' were rejected by
     * review (they appear in real coverage like 'Netflix sube el
     * precio de la suscripción estándar'); the ADDR-010 subscription
     * card ('Newsletter especial — Apúntate') is caught by its
     * imperative 'apúntate'. */
    private static final Rule SUBSCRIPTION = new Rule("subscription",
            Pattern.compile("suscr[ií]bete|ap[uú]ntate|reg[ií]strate|"
                    + "recibe cada|\\bsubscribe\\b|sign up (for|to)",
                    Pattern.CASE_INSENSITIVE));

    /** Quizzes and interactive widgets. DELIBERATELY NARROW: '\\btests?\\b'
     * was rejected by review (cricket 'tercer Test', 'tests de
     * antígenos' are real coverage); generic words ('encuesta', 'vota',
     * 'poll') likewise; the Spanish question framing '¿qué tipo de /
     * ¿qué tan' was ALSO rejected by review — real opinion/ideas
     * headlines use it ('Fondos buitre...: ¿qué tipo de ciudad
     * queremos?'). Only unambiguous widget markers qualify. The
     * ADDR-010 quiz card is caught by its URL marker; a widget whose
     * title AND url carry no marker is a known heuristic limitation
     * (the AI-assisted tier would refine it). */
    private static final Rule INTERACTIVE = new Rule("interactive-widget",
            Pattern.compile("\\bquiz\\b|\\bquizzes\\b|\\btrivia\\b|"
                    + "juega con",
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
