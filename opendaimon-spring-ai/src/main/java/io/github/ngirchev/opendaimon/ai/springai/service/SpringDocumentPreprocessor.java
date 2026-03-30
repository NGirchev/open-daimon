package io.github.ngirchev.opendaimon.ai.springai.service;

import io.github.ngirchev.opendaimon.ai.springai.config.RAGProperties;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.document.DocumentAnalysisResult;
import io.github.ngirchev.opendaimon.common.ai.document.DocumentContentType;
import io.github.ngirchev.opendaimon.common.ai.document.DocumentPreprocessingResult;
import io.github.ngirchev.opendaimon.common.ai.document.IDocumentPreprocessor;
import io.github.ngirchev.opendaimon.common.exception.DocumentContentNotExtractableException;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

/**
 * Preprocesses document attachments before the AI gateway call.
 *
 * <p>Extracted from {@code SpringAIGateway} to separate document processing
 * from model selection and chat execution. Handles:
 * <ul>
 *   <li>Text-extractable PDFs: extract text via PDFBox, index in RAG</li>
 *   <li>Image-only PDFs: render to images, OCR via VISION model, index in RAG</li>
 *   <li>Other documents: extract text via Tika, index in RAG</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class SpringDocumentPreprocessor implements IDocumentPreprocessor {

    private static final int VISION_EXTRACTION_MAX_ATTEMPTS = 3;
    private static final int VISION_EXTRACTION_LIKELY_COMPLETE_MIN_CHARS = 600;
    private static final int MAX_PDF_PAGES_TO_RENDER = 10;
    private static final int PDF_RENDER_DPI = 300;

    private final DocumentProcessingService documentProcessingService;
    private final FileRAGService fileRagService;
    private final SpringAIModelRegistry springAIModelRegistry;
    private final SpringAIChatService chatService;
    private final RAGProperties ragProperties;

    @Override
    public DocumentPreprocessingResult preprocess(Attachment attachment, String userQuery,
                                                   DocumentAnalysisResult analysisResult) {
        String documentType = SpringDocumentContentAnalyzer.extractDocumentType(
                attachment.mimeType(), attachment.filename());
        if (documentType == null) {
            log.warn("Unsupported document type for RAG: mimeType={}", attachment.mimeType());
            return DocumentPreprocessingResult.empty();
        }

        log.info("Preprocessing document: filename={}, type={}, contentType={}",
                attachment.filename(), documentType, analysisResult.contentType());

        if (analysisResult.contentType() == DocumentContentType.IMAGE_ONLY) {
            return preprocessImageOnlyPdf(attachment, documentType);
        }

        return preprocessTextExtractable(attachment, documentType);
    }

    private DocumentPreprocessingResult preprocessTextExtractable(Attachment attachment, String documentType) {
        String documentId;
        if ("pdf".equalsIgnoreCase(documentType)) {
            try {
                documentId = documentProcessingService.processPdf(attachment.data(), attachment.filename());
            } catch (DocumentContentNotExtractableException e) {
                // Text extraction failed at runtime (e.g. PdfTextDetector said "has text" but
                // TokenTextSplitter produced no chunks). Fallback to vision OCR path.
                log.info("PDF '{}' text extraction failed at runtime, falling back to vision OCR: {}",
                        attachment.filename(), e.getMessage());
                return preprocessImageOnlyPdf(attachment, documentType);
            }
        } else {
            documentId = documentProcessingService.processWithTika(
                    attachment.data(), attachment.filename(), documentType);
        }

        List<Document> relevantChunks = fileRagService.findAllByDocumentId(documentId);
        List<String> chunkTexts = relevantChunks.stream()
                .map(Document::getText)
                .toList();

        log.info("Preprocessed text document '{}': documentId={}, chunks={}",
                attachment.filename(), documentId, chunkTexts.size());

        return new DocumentPreprocessingResult(documentId, chunkTexts, List.of(), true);
    }

    private DocumentPreprocessingResult preprocessImageOnlyPdf(Attachment attachment, String documentType) {
        log.info("PDF '{}' is image-only, rendering pages as images for vision OCR", attachment.filename());

        List<Attachment> imageAttachments = renderPdfToImageAttachments(attachment.data(), attachment.filename());
        if (imageAttachments.isEmpty()) {
            log.warn("Failed to render any pages from PDF '{}'", attachment.filename());
            return DocumentPreprocessingResult.empty();
        }

        // Attempt vision OCR extraction
        String extractedText = null;
        try {
            extractedText = extractTextFromImagesViaVision(imageAttachments, attachment.filename());
        } catch (Exception ex) {
            log.warn("Vision text extraction failed for '{}': {}", attachment.filename(), ex.getMessage());
        }

        if (extractedText != null) {
            // OCR succeeded — index extracted text in RAG
            String visionDocId = documentProcessingService.processExtractedText(
                    extractedText, attachment.filename());
            if (visionDocId != null) {
                List<Document> visionChunks = fileRagService.findAllByDocumentId(visionDocId);
                List<String> chunkTexts = visionChunks.stream()
                        .map(Document::getText)
                        .toList();
                log.info("Vision OCR succeeded for '{}': documentId={}, chunks={}",
                        attachment.filename(), visionDocId, chunkTexts.size());
                return new DocumentPreprocessingResult(visionDocId, chunkTexts, List.of(), true);
            }
        }

        // OCR failed — return images as fallback for direct vision processing
        log.info("Vision OCR fallback: returning {} image(s) from PDF '{}' for direct vision",
                imageAttachments.size(), attachment.filename());
        return new DocumentPreprocessingResult(null, List.of(), imageAttachments, false);
    }

    /**
     * Renders PDF pages to images for vision model.
     * Uses PDFBox PDFRenderer at 300 DPI, limited to first 10 pages.
     */
    List<Attachment> renderPdfToImageAttachments(byte[] pdfData, String filename) {
        try (PDDocument document = Loader.loadPDF(pdfData)) {
            org.apache.pdfbox.rendering.PDFRenderer renderer =
                    new org.apache.pdfbox.rendering.PDFRenderer(document);

            int pageCount = document.getNumberOfPages();
            int pagesToRender = Math.min(pageCount, MAX_PDF_PAGES_TO_RENDER);

            if (pageCount > MAX_PDF_PAGES_TO_RENDER) {
                log.warn("PDF '{}' has {} pages, rendering only first {} pages for vision model",
                        filename, pageCount, MAX_PDF_PAGES_TO_RENDER);
            }

            List<Attachment> imageAttachments = new ArrayList<>();

            for (int pageIndex = 0; pageIndex < pagesToRender; pageIndex++) {
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, PDF_RENDER_DPI);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "PNG", baos);
                byte[] imageBytes = baos.toByteArray();

                String imageFilename = String.format("page_%d_%s.png", pageIndex + 1,
                        filename.replaceAll("\\.pdf$", ""));

                Attachment imageAttachment = new Attachment(
                        null,
                        "image/png",
                        imageFilename,
                        imageBytes.length,
                        AttachmentType.IMAGE,
                        imageBytes
                );
                imageAttachments.add(imageAttachment);
            }

            log.info("Rendered {} pages from PDF '{}' as images for vision", pagesToRender, filename);
            return imageAttachments;

        } catch (Exception e) {
            log.error("Failed to render PDF '{}' pages as images", filename, e);
            return List.of();
        }
    }

    /**
     * Extracts text content from PDF page images via a vision-capable model.
     * Selects a VISION+CHAT model from registry, sends images with extraction prompt.
     */
    private String extractTextFromImagesViaVision(List<Attachment> imageAttachments, String filename) {
        List<SpringAIModelConfig> visionCandidates = springAIModelRegistry
                .getCandidatesByCapabilities(Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION), null);
        if (visionCandidates.isEmpty()) {
            log.warn("No VISION-capable model available for text extraction from '{}'", filename);
            return null;
        }
        // Prefer concrete vision models over meta-models like "openrouter/auto"
        // which require max_price routing hints and may not find free endpoints.
        SpringAIModelConfig visionModel = visionCandidates.stream()
                .filter(m -> !m.getName().contains("/auto"))
                .findFirst()
                .orElse(visionCandidates.getFirst());
        log.info("Using vision model '{}' for text extraction from '{}'", visionModel.getName(), filename);

        String extractionPrompt = ragProperties.getPrompts().getVisionExtractionPrompt();

        List<Media> mediaList = imageAttachments.stream()
                .map(this::toMedia)
                .toList();

        UserMessage userMessage = UserMessage.builder()
                .text(extractionPrompt)
                .media(mediaList)
                .build();

        try {
            String bestExtractedText = null;
            for (int attempt = 1; attempt <= VISION_EXTRACTION_MAX_ATTEMPTS; attempt++) {
                String extractedText = chatService.callSimpleVision(visionModel, List.of(userMessage));
                if (extractedText == null || extractedText.isBlank()) {
                    log.warn("Vision extraction attempt {}/{} returned empty text for '{}'",
                            attempt, VISION_EXTRACTION_MAX_ATTEMPTS, filename);
                    continue;
                }

                extractedText = stripModelInternalTokens(extractedText);
                log.info("Vision extraction attempt {}/{} for '{}': {} chars",
                        attempt, VISION_EXTRACTION_MAX_ATTEMPTS, filename, extractedText.length());

                if (!extractedText.isBlank()
                        && (bestExtractedText == null || extractedText.length() > bestExtractedText.length())) {
                    bestExtractedText = extractedText;
                }

                if (isLikelyCompleteVisionExtraction(bestExtractedText)) {
                    break;
                }
            }

            if (bestExtractedText != null && !bestExtractedText.isBlank()) {
                log.info("Vision extraction succeeded for '{}': {} chars", filename, bestExtractedText.length());
                log.debug("Vision extracted text for '{}': [{}]", filename, bestExtractedText);
                return bestExtractedText;
            }

            log.warn("Vision extraction returned empty text for '{}'", filename);
            return null;
        } catch (Exception e) {
            log.error("Vision extraction failed for '{}': {}", filename, e.getMessage());
            return null;
        }
    }

    /**
     * Strips model-internal tokens (e.g. {@code <start_of_image>}, {@code <end_of_turn>})
     * that some vision models (gemma3, llava) leak into their text output.
     */
    public static String stripModelInternalTokens(String text) {
        if (text == null) return null;
        return text.replaceAll("<start_of_image>|<end_of_image>|<end_of_turn>|<start_of_turn>", "")
                .strip();
    }

    private static boolean isLikelyCompleteVisionExtraction(String text) {
        return text != null && text.length() >= VISION_EXTRACTION_LIKELY_COMPLETE_MIN_CHARS;
    }

    private Media toMedia(Attachment attachment) {
        var mimeType = MimeTypeUtils.parseMimeType(attachment.mimeType());
        var resource = new ByteArrayResource(attachment.data());
        return new Media(mimeType, resource);
    }
}
