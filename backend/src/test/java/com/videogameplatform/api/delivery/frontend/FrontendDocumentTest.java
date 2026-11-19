package com.videogameplatform.api.delivery.frontend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FrontendDocumentTest {

    @Test
    void shortDescriptionsOnlyCollapseWhitespace() {
        assertThat(FrontendDocument.describe("  Un  juego\n\tbreve.  "))
                .isEqualTo("Un juego breve.");
    }

    @Test
    void longDescriptionsAreCutAtAWordBoundaryWithAnEllipsis() {
        String text = "palabra ".repeat(40) + "final.";

        String described = FrontendDocument.describe(text);

        assertThat(described.codePointCount(0, described.length()))
                .isLessThanOrEqualTo(FrontendDocument.DESCRIPTION_LIMIT);
        assertThat(described).endsWith("palabra…").doesNotContain("  ");
    }

    @Test
    void trailingPunctuationBeforeTheEllipsisIsDropped() {
        String text = "a".repeat(100) + ", " + "b".repeat(100);

        assertThat(FrontendDocument.describe(text)).isEqualTo("a".repeat(100) + "…");
    }

    @Test
    void aTextWithoutLateBoundariesIsCutWithoutSplittingCodePoints() {
        String text = "😀".repeat(200);

        String described = FrontendDocument.describe(text);

        assertThat(described).isEqualTo("😀".repeat(FrontendDocument.DESCRIPTION_LIMIT - 1) + "…");
    }

    @Test
    void refusesATemplateWithoutExactlyOneMetadataBlock() {
        assertThatThrownBy(() -> new FrontendDocument("<html><head></head></html>"))
                .isInstanceOf(IllegalStateException.class);
        String block = FrontendDocument.START + FrontendDocument.END;
        assertThatThrownBy(() -> new FrontendDocument(block + block))
                .isInstanceOf(IllegalStateException.class);
    }
}
