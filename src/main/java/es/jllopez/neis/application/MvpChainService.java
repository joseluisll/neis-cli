package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.PageAnalysis;
import es.jllopez.neis.domain.model.PaginationInfo;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.model.SimplifiedContent;
import es.jllopez.neis.domain.port.AiAssistantPort;
import es.jllopez.neis.domain.port.ContentScrapePort;
import es.jllopez.neis.domain.port.SelectorExtractionPort;
import es.jllopez.neis.domain.port.SimplifyPort;
import es.jllopez.neis.domain.service.PaginationDetector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Use case: the predefined static MVP chain
 * SCRAPE - SIMPLIFY - ANALYZE - PRINT (issue #90), executed sequentially
 * for one address of the static catalog (chain slice of US-08.1/US-08.2,
 * issues #33/#34).
 *
 * <p>Depends only on domain ports and services; the jsoup and Spring AI
 * implementations sit behind the ports in infrastructure (US-01.4 rule).
 * The AI call goes through {@link AiAssistantPort} and is optional: when
 * unconfigured or failing, the chain degrades to the address-type
 * classification and heuristic selectors instead of failing.</p>
 */
@Service
public class MvpChainService {

    /** Maximum items printed per address (US-12.5 spirit). */
    private static final int MAX_ITEMS = 50;

    /** Characters of simplified HTML fed to the AI prompt. */
    private static final int AI_HTML_BUDGET = 18_000;

    private static final List<String> FALLBACK_NEWS_SELECTORS =
            List.of("article", ".news-item", ".news-entry", "ul li", "a[href]");
    private static final List<String> FALLBACK_EVENT_SELECTORS =
            List.of(".event-item", ".event-entry", "article", "ul li", "a[href]");

    private final ContentScrapePort scrapePort;
    private final SimplifyPort simplifyPort;
    private final SelectorExtractionPort extractionPort;
    private final PaginationDetector paginationDetector;
    private final ObjectProvider<AiAssistantPort> aiPort;
    private final AddressCatalogService addressCatalog;
    private final String workDir;

    public MvpChainService(ContentScrapePort scrapePort,
                           SimplifyPort simplifyPort,
                           SelectorExtractionPort extractionPort,
                           PaginationDetector paginationDetector,
                           ObjectProvider<AiAssistantPort> aiPort,
                           AddressCatalogService addressCatalog,
                           @Value("${neis.mvp.work-dir:./neis-mvp}") String workDir) {
        this.scrapePort = scrapePort;
        this.simplifyPort = simplifyPort;
        this.extractionPort = extractionPort;
        this.paginationDetector = paginationDetector;
        this.aiPort = aiPort;
        this.addressCatalog = addressCatalog;
        this.workDir = workDir;
    }

    /** Runs the whole static chain for one address. */
    public MvpChainResult run(Address address) {
        // SCRAPE (US-06.1 slice): obtain HTML5 for the address.
        ScrapeResult scrape = scrapePort.scrape(address);
        Path addressDir = addressDir(address);
        Path scrapeArtifact = writeArtifact(addressDir, "scrape.html", scrape.html());

        // SIMPLIFY (US-07.1..3 slice): cleanse to action/data elements <= 100 KB.
        SimplifiedContent simplified = simplifyPort.simplify(scrape.html());
        Path simplifyArtifact = writeArtifact(addressDir, "simplified.html", simplified.html());

        // ANALYZE (US-12.4 + US-13.2..4 slices): pagination, classification, selectors.
        PaginationInfo pagination = paginationDetector.detect(simplified.html());
        PageAnalysis analysis = analyze(simplified.html(), address);

        // Selector selection: AI-identified first, validated by a probe
        // extraction; heuristic fallbacks otherwise (US-12.3 spirit).
        boolean selectorFromAi = false;
        String selected = switch (address.type()) {
            case URL_NEWS -> analysis.newsSelector();
            case URL_EVENTS -> analysis.eventsSelector();
        };
        if (selected != null && !selected.isBlank()
                && !extractionPort.extract(simplified.html(), selected,
                        address.itemKind(), address.id(), 1).isEmpty()) {
            selectorFromAi = analysis.aiAssisted();
        } else {
            selected = null;
            for (String candidate : fallbackSelectors(address)) {
                if (!extractionPort.extract(simplified.html(), candidate,
                        address.itemKind(), address.id(), 1).isEmpty()) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) {
                selected = fallbackSelectors(address)
                        .get(fallbackSelectors(address).size() - 1);
            }
        }

        // PRINT input: selector-based extraction of the discovered items.
        List<NewsEventItem> items = extractionPort.extract(simplified.html(), selected,
                address.itemKind(), address.id(), MAX_ITEMS);

        return new MvpChainResult(address, scrapeArtifact.toString(),
                simplifyArtifact.toString(), scrape.sizeBytes(), simplified.sizeBytes(),
                pagination, analysis, selected, selectorFromAi, items);
    }

    /** Runs the chain for every address of the static catalog. */
    public List<MvpChainResult> runAll() {
        List<MvpChainResult> results = new ArrayList<>();
        for (Address address : addressCatalog.list()) {
            results.add(run(address));
        }
        return results;
    }

    private List<String> fallbackSelectors(Address address) {
        return address.type() == AddressType.URL_NEWS
                ? FALLBACK_NEWS_SELECTORS : FALLBACK_EVENT_SELECTORS;
    }

    private Path addressDir(Address address) {
        Path dir = Path.of(workDir, address.id());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create chain work dir " + dir, e);
        }
        return dir;
    }

    private Path writeArtifact(Path dir, String fileName, String content) {
        try {
            Path file = dir.resolve(fileName);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write chain artifact " + fileName, e);
        }
    }

    /**
     * ANALYZE via the AI port (classification + selector identification).
     * Falls back to the address type when the AI point is not configured
     * or the call/parsing fails — the chain never crashes on AI absence.
     */
    private PageAnalysis analyze(String simplifiedHtml, Address address) {
        AiAssistantPort ai = aiPort.getIfAvailable();
        if (ai == null) {
            return PageAnalysis.fallback(address.itemKind(),
                    "AI integration point not configured (neis.ai.enabled / MELIOUS_API_KEY)");
        }
        try {
            String answer = ai.complete(buildPrompt(simplifiedHtml, address));
            PageAnalysis parsed = parseAnalysis(answer);
            if (parsed != null) {
                return parsed;
            }
            return PageAnalysis.fallback(address.itemKind(),
                    "AI reply could not be parsed into the expected JSON");
        } catch (RuntimeException e) {
            return PageAnalysis.fallback(address.itemKind(),
                    "AI call failed: " + e.getMessage());
        }
    }

    private String buildPrompt(String simplifiedHtml, Address address) {
        String html = simplifiedHtml.length() > AI_HTML_BUDGET
                ? simplifiedHtml.substring(0, AI_HTML_BUDGET)
                : simplifiedHtml;
        return """
                You are analyzing the simplified HTML5 of a web page that should list \
                news items or events. Perform two tasks and answer ONLY with a JSON object:
                1. Classify the page content as one of: "news", "event" or "other".
                2. Identify a CSS selector, valid against the given HTML, that matches the \
                repeating list items of news (news_selector), and one that matches the \
                repeating list items of events (events_selector). Only fill the selector \
                relevant to the page; use null for the other.
                Expected page type hint: %s
                Answer format: {"classification":"news|event|other","news_selector":"..." \
                or null,"events_selector":"..." or null,"reasoning":"short explanation"}
                --- SIMPLIFIED HTML (truncated) ---
                %s""".formatted(address.itemKind(), html);
    }

    /** Lenient parse of the AI JSON reply; null when not parseable. */
    private PageAnalysis parseAnalysis(String reply) {
        if (reply == null) {
            return null;
        }
        String classification = jsonField(reply, "classification");
        String newsSelector = jsonField(reply, "news_selector");
        String eventsSelector = jsonField(reply, "events_selector");
        String reasoning = jsonField(reply, "reasoning");
        if (classification == null) {
            return null;
        }
        return new PageAnalysis(classification, newsSelector, eventsSelector,
                true, reasoning == null ? "" : reasoning);
    }

    private String jsonField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|\"([^\"]*)\")")
                .matcher(json);
        if (!m.find() || m.group(1).equals("null")) {
            return null;
        }
        return m.group(2);
    }
}
