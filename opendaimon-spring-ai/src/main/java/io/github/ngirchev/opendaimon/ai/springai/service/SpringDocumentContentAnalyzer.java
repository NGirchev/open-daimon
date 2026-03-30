package io.github.ngirchev.opendaimon.ai.springai.service;

import io.github.ngirchev.opendaimon.common.ai.document.DocumentAnalysisResult;
import io.github.ngirchev.opendaimon.common.ai.document.IDocumentContentAnalyzer;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Analyzes document content to determine required model capabilities.
 *
 * <p>For PDF: uses {@link PdfTextDetector} to check if text is extractable.
 * If not — the document is image-only and requires VISION for OCR.
 *
 * <p>For all other document types (DOCX, TXT, etc.): Tika can always extract
 * text, so they are always classified as {@code TEXT_EXTRACTABLE}.
 */
@Slf4j
@RequiredArgsConstructor
public class SpringDocumentContentAnalyzer implements IDocumentContentAnalyzer {

    private final PdfTextDetector pdfTextDetector;

    /**
     * Known document type mappings: MIME type patterns and file extensions.
     * PDF is listed first as it has special handling (PDFBox vs Tika).
     */
    private static final List<DocumentTypeMapping> DOCUMENT_TYPE_MAPPINGS = List.of(
            new DocumentTypeMapping("pdf", List.of("pdf"), List.of(".pdf")),
            new DocumentTypeMapping("docx", List.of("wordprocessingml"), List.of(".docx")),
            new DocumentTypeMapping("doc", List.of("msword"), List.of(".doc")),
            new DocumentTypeMapping("xlsx", List.of("spreadsheetml"), List.of(".xlsx")),
            new DocumentTypeMapping("xls", List.of("ms-excel", "msexcel"), List.of(".xls")),
            new DocumentTypeMapping("pptx", List.of("presentationml"), List.of(".pptx")),
            new DocumentTypeMapping("ppt", List.of("ms-powerpoint", "mspowerpoint"), List.of(".ppt")),
            new DocumentTypeMapping("txt", List.of("text/plain"), List.of(".txt")),
            new DocumentTypeMapping("rtf", List.of("rtf"), List.of(".rtf")),
            new DocumentTypeMapping("odt", List.of("opendocument.text"), List.of(".odt")),
            new DocumentTypeMapping("ods", List.of("opendocument.spreadsheet"), List.of(".ods")),
            new DocumentTypeMapping("odp", List.of("opendocument.presentation"), List.of(".odp")),
            new DocumentTypeMapping("csv", List.of("csv"), List.of(".csv")),
            new DocumentTypeMapping("html", List.of("text/html"), List.of(".html", ".htm")),
            new DocumentTypeMapping("md", List.of("markdown"), List.of(".md", ".markdown")),
            new DocumentTypeMapping("json", List.of("json"), List.of(".json")),
            new DocumentTypeMapping("xml", List.of("xml"), List.of(".xml")),
            new DocumentTypeMapping("epub", List.of("epub"), List.of(".epub"))
    );

    @Override
    public DocumentAnalysisResult analyze(Attachment attachment) {
        String documentType = extractDocumentType(attachment.mimeType(), attachment.filename());
        if (documentType == null) {
            log.debug("Unsupported document type: mimeType={}, filename={}", attachment.mimeType(), attachment.filename());
            return DocumentAnalysisResult.unsupported();
        }

        if (!"pdf".equalsIgnoreCase(documentType)) {
            // Non-PDF documents are always text-extractable via Tika
            return DocumentAnalysisResult.textExtractable();
        }

        // PDF: check if text layer exists
        boolean hasText = pdfTextDetector.hasExtractableText(attachment.data(), attachment.filename());
        if (hasText) {
            log.debug("PDF '{}' has extractable text — CHAT is sufficient", attachment.filename());
            return DocumentAnalysisResult.textExtractable();
        }

        log.info("PDF '{}' is image-only — VISION required for OCR", attachment.filename());
        return DocumentAnalysisResult.requiresVision();
    }

    /**
     * Resolves document type from MIME type or filename.
     * Extracted from SpringAIGateway for reuse.
     */
    public static String extractDocumentType(String mimeType, String filename) {
        if (mimeType == null && filename == null) {
            return null;
        }

        String type = mimeType != null ? mimeType.toLowerCase() : "";
        String name = filename != null ? filename.toLowerCase() : "";

        return DOCUMENT_TYPE_MAPPINGS.stream()
                .filter(mapping -> mapping.matches(type, name))
                .map(DocumentTypeMapping::documentType)
                .findFirst()
                .orElse(null);
    }

    /**
     * Maps MIME type patterns and file extensions to a document type identifier.
     */
    public record DocumentTypeMapping(
            String documentType,
            List<String> mimeTypePatterns,
            List<String> fileExtensions
    ) {
        boolean matches(String mimeType, String filename) {
            boolean mimeMatches = mimeTypePatterns.stream().anyMatch(mimeType::contains);
            boolean extensionMatches = fileExtensions.stream().anyMatch(filename::endsWith);
            return mimeMatches || extensionMatches;
        }
    }
}
