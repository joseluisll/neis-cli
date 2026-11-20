package es.jllopez.neis.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The item-relevance classifier (US-13.9, issue #136): promo and
 * utility cards are excluded with a NAMED reason; real articles pass.
 * The live ADDR-010 evidence pins the rule set. */
class NewsItemRelevanceTest {

    private NewsEventItem item(String title, String url) {
        return new NewsEventItem("ADDR-1", "news", title,
                "Description", url, "2026-11-19");
    }

    @Test
    void theLiveAddr010EvidenceIsExcludedWithReasons() {
        // The four live cases from ADDR-010 (ABC), 2026-11-19.
        var quiz = NewsItemRelevance.exclusionOf(item(
                "¿Qué partido está más de tus ideas políticas?",
                "https://www.abc.es/x/quiz-20261119222527-nt.html"));
        assertNotNull(quiz, "the quiz widget is excluded (its URL "
                + "carries the interactive marker; a widget with no "
                + "marker in title AND url is the documented heuristic "
                + "limitation)");
        assertEquals("interactive-widget", quiz.rule());

        var newsletter = NewsItemRelevance.exclusionOf(item(
                "Newsletter especial — Apúntate",
                "https://www.abc.es/x/newsletter-20261119222527-nt.html"));
        assertNotNull(newsletter, "the subscription card is excluded");
        assertEquals("subscription", newsletter.rule());

        var campaign = NewsItemRelevance.exclusionOf(item(
                "Toda la información de la campaña",
                "https://www.abc.es/x/campana-20261119222527-nt.html"));
        assertNotNull(campaign, "the campaign hub link is excluded");
        assertEquals("section-hub", campaign.rule());

        var legal = NewsItemRelevance.exclusionOf(item(
                "Política de privacidad",
                "https://www.abc.es/privacidad/"));
        assertNotNull(legal, "the legal link is excluded");
        assertEquals("utility-page", legal.rule());
    }

    @Test
    void hubShapedUrlsAreExcludedEvenWithANeutralTitle() {
        var hub = NewsItemRelevance.exclusionOf(item(
                "Elecciones",
                "https://www.abc.es/tags/elecciones/"));
        assertNotNull(hub, "a tag/container URL is not an article page");
        assertEquals("hub-url", hub.rule());
    }

    @Test
    void realArticlesPass() {
        assertNull(NewsItemRelevance.exclusionOf(item(
                "Cómo van las encuestas del 29N",
                "https://www.abc.es/x/encuestas-20261119222527-nt.html")),
                "a real news article passes — 'encuestas' inside a longer "
                        + "word or URL must not trip the interactive rule");
        assertNull(NewsItemRelevance.exclusionOf(item(
                "El ministerio de Transportes pagó un tratmiento irregular",
                "https://www.abc.es/x/transportes-20261119222527-nt.html")));
        assertNull(NewsItemRelevance.exclusionOf(item(
                "El Congreso aprueba la ley de menores",
                "https://www.abc.es/x/congreso-20261119222527-nt.html")),
                "a plain political news piece passes");
    }

    @Test
    void nullAndBlankAreSafe() {
        assertNull(NewsItemRelevance.exclusionOf(null));
        assertNull(NewsItemRelevance.exclusionOf(item(null, null)),
                "an empty item is the store's incomplete path, not ours");
    }
}
