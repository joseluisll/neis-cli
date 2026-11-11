package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.Address;
import es.jllopez.neis.domain.model.AddressType;
import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.model.PageAnalysis;
import es.jllopez.neis.domain.model.PaginationInfo;
import es.jllopez.neis.domain.model.ScrapeResult;
import es.jllopez.neis.domain.model.SimplifiedContent;
import es.jllopez.neis.domain.model.StoredSelectors;
import es.jllopez.neis.domain.port.AiAssistantPort;
import es.jllopez.neis.domain.port.ContentScrapePort;
import es.jllopez.neis.domain.port.SelectorExtractionPort;
import es.jllopez.neis.domain.port.SelectorStorePort;
import es.jllopez.neis.domain.port.SimplifyPort;
import es.jllopez.neis.domain.service.PaginationDetector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Use case: the predefined static MVP chain
 * SCRAPE - SIMPLIFY - ANALYZE - PRINT (issue #90), executed sequentially
 * for one address of the static catalog (chain slice of US-08.1/US-08.2,
 * issues #33/#34).
 *
 * <p>ANALYZE selector strategy (owner decision 2026-11-11): a stored,
 * previously validated selector for the address is used directly and the
 * AI is skipped entirely (fast path). Only when no working selector is
 * stored does the chain invoke the AI — one task-specific prompt per
 * concern from {@link AiPromptCatalog} (classification, then news or
 * events selector identification) — validate the identified selector by
 * probe extraction, and persist it with the address so future runs need
 * no AI. Static heuristic selectors remain as last-resort fallback and
 * are never persisted.</p>
 */
@Service
public class MvpChainService {

    /** Maximum items extracted per address (US-12.5 spirit). */
    private static final int MAX_ITEMS = 50;

    private static final List<String> FALLBACK_NEWS_SELECTORS =
            List.of("ul.news-list li", ".news-item", ".news-entry", "article",
                    "ul li", "a[href]");
    private static final List<String> FALLBACK_EVENT_SELECTORS =
            List.of(".event-item", ".event-entry", "article", "ul li", "a[href]");

    private final ContentScrapePort scrapePort;
    private final SimplifyPort simplifyPort;
    private final SelectorExtractionPort extractionPort;
    private final SelectorStorePort selectorStore;
    private final PaginationDetector paginationDetector;
    private final ObjectProvider<AiAssistantPort> aiPort;
    private final AddressCatalogService addressCatalog;
    private final String workDir;

    public MvpChainService(ContentScrapePort scrapePort,
                           SimplifyPort simplifyPort,
                           SelectorExtractionPort extractionPort,
                           SelectorStorePort selectorStore,
                           PaginationDetector paginationDetector,
                           ObjectProvider<AiAssistantPort> aiPort,
                           AddressCatalogService addressCatalog,
                           @Value("${neis.mvp.work-dir:./neis-mvp}") String workDir) {
        this.scrapePort = scrapePort;
        this.simplifyPort = simplifyPort;
        this.extractionPort = extractionPort;
        this.selectorStore = selectorStore;
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

        // ANALYZE (US-12.4 slice): pagination detection.
        PaginationInfo pagination = paginationDetector.detect(simplified.html());

        // ANALYZE (US-13.2..4 slices): classification + working selector.
        SelectorSelection selection = selectSelector(address, simplified.html());

        // PRINT input: selector-based extraction of the discovered items.
        List<NewsEventItem> items = extractionPort.extract(simplified.html(),
                selection.selector(), address.itemKind(), address.id(), MAX_ITEMS);

        return new MvpChainResult(address, scrapeArtifact.toString(),
                simplifyArtifact.toString(), scrape.sizeBytes(), simplified.sizeBytes(),
                pagination, selection.analysis(), selection.selector(),
                selection.source(), items);
    }

    /** Runs the chain for every address of the static catalog. */
    public List<MvpChainResult> runAll() {
        List<MvpChainResult> results = new ArrayList<>();
        for (Address address : addressCatalog.list()) {
            results.add(run(address));
        }
        return results;
    }

    /**
     * Selector strategy: stored selector first (no AI), then AI
     * identification via the prompt catalog (persisted once validated),
     * then static heuristic fallbacks (never persisted).
     */
    private SelectorSelection selectSelector(Address address, String simplifiedHtml) {
        String kind = address.itemKind();

        // 1. Stored working selector: fast path, AI entirely skipped.
        Optional<StoredSelectors> stored = selectorStore.load(address.id());
        if (stored.isPresent()) {
            StoredSelectors s = stored.get();
            if (valid(simplifiedHtml, s.selector(), kind, address)) {
                return new SelectorSelection(s.selector(), "stored",
                        new PageAnalysis(s.kind(), s.kind().equals("news") ? s.selector() : null,
                                s.kind().equals("event") ? s.selector() : null, false,
                                "Stored selector (discovered " + s.discoveredAt()
                                        + " by " + s.discoveredBy() + "); AI skipped. "
                                        + s.notes()));
            }
            // Stored selector went stale -> fall through to re-identification.
        }

        // 2. AI identification: classification prompt, then the selector
        //    prompt for the page's kind. Persisted when the probe validates it.
        AiAssistantPort ai = aiPort.getIfAvailable();
        String classification = kind;
        String aiNotes = null;
        if (ai != null) {
            try {
                String classAnswer = ai.complete(
                        AiPromptCatalog.classificationPrompt(simplifiedHtml, kind));
                String parsed = parseClassification(classAnswer);
                if (parsed != null) {
                    classification = parsed;
                }
                String selectorAnswer = ai.complete(
                        kind.equals("news")
                                ? AiPromptCatalog.newsSelectorPrompt(simplifiedHtml)
                                : AiPromptCatalog.eventsSelectorPrompt(simplifiedHtml));
                String selector = jsonField(selectorAnswer, "selector");
                String reasoning = jsonField(selectorAnswer, "reasoning");
                if (selector != null && !selector.isBlank()
                        && valid(simplifiedHtml, selector, kind, address)) {
                    selectorStore.save(new StoredSelectors(address.id(), kind,
                            selector, "ai", LocalDate.now().toString(),
                            reasoning == null ? "" : reasoning));
                    return new SelectorSelection(selector, "ai",
                            new PageAnalysis(classification,
                                    kind.equals("news") ? selector : null,
                                    kind.equals("event") ? selector : null,
                                    true, reasoning == null ? "" : reasoning));
                }
                aiNotes = "AI did not yield a valid selector"
                        + (reasoning == null || reasoning.isBlank() ? "" : ": " + reasoning);
            } catch (RuntimeException e) {
                aiNotes = "AI call failed: " + e.getMessage();
            }
        } else {
            aiNotes = "AI integration point not configured (neis.ai.enabled / MELIOUS_API_KEY)";
        }

        // 3. Static heuristic fallback (not persisted — the point is to
        //    store AI-identified working selectors only).
        String selected = null;
        for (String candidate : fallbackSelectors(address)) {
            if (valid(simplifiedHtml, candidate, kind, address)) {
                selected = candidate;
                break;
            }
        }
        if (selected == null) {
            selected = fallbackSelectors(address).getLast();
        }
        return new SelectorSelection(selected, "fallback",
                PageAnalysis.fallback(kind,
                        (aiNotes == null ? "AI did not yield a valid selector" : aiNotes)
                                + " — heuristic selector used"));
    }

    private boolean valid(String simplifiedHtml, String selector, String kind,
                          Address address) {
        return selector != null && !selector.isBlank()
                && !extractionPort.extract(simplifiedHtml, selector, kind,
                        address.id(), 1).isEmpty();
    }

    private List<String> fallbackSelectors(Address address) {
        return address.type() == AddressType.URL_NEWS
                ? FALLBACK_NEWS_SELECTORS : FALLBACK_EVENT_SELECTORS;
    }

    /** Parses the single-word classification answer; null when unusable. */
    private String parseClassification(String answer) {
        if (answer == null) {
            return null;
        }
        String word = answer.trim().toLowerCase();
        if (word.startsWith("news")) {
            return "news";
        }
        if (word.startsWith("event")) {
            return "event";
        }
        if (word.startsWith("other")) {
            return "other";
        }
        return null;
    }

    private String jsonField(String json, String field) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(null|\"([^\"]*)\")")
                .matcher(json);
        if (!m.find() || m.group(1).equals("null")) {
            return null;
        }
        return m.group(2);
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

    /** Chosen selector plus its provenance and the page analysis. */
    record SelectorSelection(String selector, String source, PageAnalysis analysis) {
    }
}
