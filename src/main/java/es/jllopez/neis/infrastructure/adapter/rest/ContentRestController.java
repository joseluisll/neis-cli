package es.jllopez.neis.infrastructure.adapter.rest;

import es.jllopez.neis.infrastructure.adapter.ConditionalOnServerEnabled;

import es.jllopez.neis.application.ContentStoreService;
import es.jllopez.neis.domain.model.ContentRecord;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Content-store queries over REST (US-18.1, issue #116): the captured
 * artifacts' records (the artifacts themselves live in the content
 * root; reading their bytes is US-18.8's export slice).
 */
@RestController
@RequestMapping("/api/content")
@ConditionalOnServerEnabled
public class ContentRestController {

    private final ContentStoreService content;

    public ContentRestController(ContentStoreService content) {
        this.content = content;
    }

    @GetMapping
    public List<ContentRecord> list(
            @RequestParam(name = "addressId", required = false) String addressId) {
        return addressId == null || addressId.isBlank()
                ? content.listAll() : content.listByAddress(addressId.trim());
    }

    @GetMapping("/{id}")
    public ContentRecord show(@PathVariable String id) {
        return content.byId(id).orElseThrow(() ->
                new java.util.NoSuchElementException("Unknown content record: " + id));
    }

    /** The content root and the records grouped per address — the
     * storage tree (US-11.4) in one payload. */
    @GetMapping("/tree")
    public TreeView tree() {
        List<ContentRecord> records = content.listAll();
        return new TreeView(content.root(), records);
    }

    public record TreeView(String root, List<ContentRecord> records) {
    }
}
