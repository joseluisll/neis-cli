package es.jllopez.neis.infrastructure.adapter.rest;

import es.jllopez.neis.infrastructure.adapter.ConditionalOnServerEnabled;
import es.jllopez.neis.application.RemoteExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;

/**
 * The download endpoint of the content export (US-18.8, issue #126):
 * streams a finished export's ZIP by token ({@code application/zip},
 * {@code Content-Disposition: attachment}) — the remote caller fetches
 * it with a plain HTTP GET after the job reports the path. Token-based
 * and bounded: the token is valid while the exported archive exists
 * (the retention cleanup removes it); a crafted token is refused, an
 * unknown/expired one is gone. Behind the same RemoteAuthFilter attach
 * point as every other /api endpoint; only exists in server mode (the
 * controller is conditional on the server flag like its siblings).
 */
@RestController
@RequestMapping("/api/exports")
@ConditionalOnServerEnabled
public class ExportsRestController {

    private final RemoteExportService exports;

    public ExportsRestController(RemoteExportService exports) {
        this.exports = exports;
    }

    @GetMapping("/{token}")
    public ResponseEntity<org.springframework.core.io.Resource> download(
            @PathVariable String token) {
        Path archive = exports.archive(token).orElseThrow(() ->
                new NoSuchElementException("No exported archive for token: "
                        + token + " (expired or unknown; US-18.8)"));
        try {
            var resource = new org.springframework.core.io.FileSystemResource(
                    archive);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + archive.getFileName()
                                    + "\"")
                    .contentLength(Files.size(archive))
                    .body(resource);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot stream the export archive " + archive, e);
        }
    }
}
