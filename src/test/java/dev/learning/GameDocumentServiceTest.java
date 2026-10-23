package dev.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;

class GameDocumentServiceTest {
    @Test
    void emptyScanDoesNotProduceAnIndexablePassage() {
        assertEquals(List.of(), GameDocumentService.chunks("  \n  ", 900));
        List<String> passages = GameDocumentService.chunks("  event   rules \n moderation queue  ", 12);
        assertEquals(List.of("event rules ", "moderation ", "queue"), passages);
        assertTrue(passages.stream().allMatch(p -> p.length() <= 12));
    }
}
