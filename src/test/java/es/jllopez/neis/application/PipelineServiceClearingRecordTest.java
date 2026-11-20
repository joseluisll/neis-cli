package es.jllopez.neis.application;

import es.jllopez.neis.domain.model.ClearedContent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The clearing-record JSON of a capture (US-06.7/V15, extended by
 * US-06.13 #137): the serialized STRING is pinned because the stored
 * column is opaque TEXT — a shape regression would land in the
 * capture provenance unnoticed by the ClearedContent-level tests. */
class PipelineServiceClearingRecordTest {

    @Test
    void dialogsRideOnTheRecordAsTheExtendedShape() {
        var cleared = new ClearedContent("<p/>", 4, 2,
                List.of("consent-banners"),
                List.of(new ClearedContent.DialogEncounter("consent", 2,
                        "dismissed (removed from the capture); consent "
                                + "policy REJECT recorded")));
        assertEquals("{\"removed\":2,\"rules\":[\"consent-banners\"],"
                + "\"dialogs\":[{\"type\":\"consent\",\"count\":2,"
                + "\"action\":\"dismissed (removed from the capture); "
                + "consent policy REJECT recorded\"}]}",
                PipelineService.clearingRecord(cleared),
                "the V15 JSON carries the classified dialogs");
    }

    @Test
    void noDialogsKeepsTheLegacyShapeByteIdentical() {
        var cleared = new ClearedContent("<p/>", 4, 1,
                List.of("advertisements"));
        assertEquals("{\"removed\":1,\"rules\":[\"advertisements\"]}",
                PipelineService.clearingRecord(cleared),
                "a page without classified overlays serializes exactly "
                        + "the pre-US-06.13 shape");
    }
}
