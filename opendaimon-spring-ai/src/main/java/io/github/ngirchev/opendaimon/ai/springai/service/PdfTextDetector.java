package io.github.ngirchev.opendaimon.ai.springai.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Lightweight detector that checks whether a PDF has extractable text.
 *
 * <p>Uses PDFBox {@link PDFTextStripper} to attempt text extraction without
 * chunking, embedding, or writing to VectorStore. This makes it suitable
 * for use in the command factory before the gateway call — the result informs
 * capability routing (CHAT vs CHAT+VISION).
 */
@Slf4j
public class PdfTextDetector {

    private static final int MIN_MEANINGFUL_TEXT_LENGTH = 10;

    /**
     * Checks whether the given PDF data contains extractable text.
     *
     * @param pdfData raw PDF bytes
     * @param filename original filename (for logging)
     * @return true if PDF has a text layer with meaningful content
     */
    public boolean hasExtractableText(byte[] pdfData, String filename) {
        if (pdfData == null || pdfData.length == 0) {
            log.warn("PdfTextDetector: empty PDF data for '{}'", filename);
            return false;
        }

        try (PDDocument document = Loader.loadPDF(pdfData)) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Check only first few pages for performance
            int maxPages = Math.min(document.getNumberOfPages(), 3);
            stripper.setEndPage(maxPages);

            String text = stripper.getText(document);
            String stripped = text != null ? text.strip() : "";

            boolean hasText = stripped.length() >= MIN_MEANINGFUL_TEXT_LENGTH;
            log.debug("PdfTextDetector: '{}' hasText={}, extractedLength={}, pagesChecked={}",
                    filename, hasText, stripped.length(), maxPages);
            return hasText;
        } catch (Exception e) {
            log.warn("PdfTextDetector: failed to analyze '{}': {}", filename, e.getMessage());
            return false;
        }
    }
}
