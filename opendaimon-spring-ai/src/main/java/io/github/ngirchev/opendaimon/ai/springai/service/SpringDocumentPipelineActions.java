package io.github.ngirchev.opendaimon.ai.springai.service;

import io.github.ngirchev.opendaimon.ai.springai.config.RAGProperties;
import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.ai.springai.rag.FileRAGService;
import io.github.ngirchev.opendaimon.ai.springai.retry.SpringAIModelRegistry;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import io.github.ngirchev.opendaimon.common.ai.document.DocumentAnalysisResult;
import io.github.ngirchev.opendaimon.common.ai.document.DocumentContentType;
import io.github.ngirchev.opendaimon.common.ai.document.IDocumentContentAnalyzer;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.AttachmentProcessingContext;
import io.github.ngirchev.opendaimon.common.ai.pipeline.fsm.DocumentPipelineActions;
import io.github.ngirchev.opendaimon.common.exception.DocumentContentNotExtractableException;
import io.github.ngirchev.opendaimon.common.model.Attachment;
import io.github.ngirchev.opendaimon.common.model.AttachmentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Spring AI implementation of {@link DocumentPipelineActions}.
 *
 * <p>Ports logic from {@code SpringDocumentOrchestrator}, {@code SpringDocumentPreprocessor},
 * and {@code SpringDocumentContentAnalyzer} into discrete FSM action methods.
 *
 * <p>Each method corresponds to a single FSM transition action and populates
 * the {@link AttachmentProcessingContext} with results for subsequent transitions.
 */
@Slf4j
@RequiredArgsConstructor
public class SpringDocumentPipelineActions implements DocumentPipelineActions {

    private static final int VISION_EXTRACTION_MAX_ATTEMPTS = 3;
    private static final int VISION_EXTRACTION_LIKELY_COMPLETE_MIN_CHARS = 600;
    private static final int MAX_PDF_PAGES_TO_RENDER = 10;
    private static final int PDF_RENDER_DPI = 300;

    private final IDocumentContentAnalyzer documentContentAnalyzer;
    private final DocumentProcessingService documentProcessingService;
    private final FileRAGService fileRagService;
    private final SpringAIModelRegistry springAIModelRegistry;
    private final SpringAIChatService chatService;
    private final RAGProperties ragProperties;

    @Override
    public void classify(AttachmentProcessingContext ctx) {
        Attachment attachment = ctx.getAttachment();
        ctx.setProcessedFilename(attachment.filename());
        log.debug("FSM classify: filename={}, mimeType={}, isImage={}, isDocument={}",
                attachment.filename(), attachment.mimeType(), attachment.isImage(), attachment.isDocument());
    }

    @Override
    public void analyzeContent(AttachmentProcessingContext ctx) {
        Attachment attachment = ctx.getAttachment();
        DocumentAnalysisResult analysisResult = documentContentAnalyzer.analyze(attachment);
        ctx.setDocumentContentType(analysisResult.contentType());
        log.info("FSM analyzeContent: filename={}, contentType={}",
                attachment.filename(), analysisResult.contentType());
    }

    @Override
    public void extractText(AttachmentProcessingContext ctx) {
        Attachment attachment = ctx.getAttachment();
        String documentType = SpringDocumentContentAnalyzer.extractDocumentType(
                attachment.mimeType(), attachment.filename());

        try {
            String documentId;
            if ("pdf".equalsIgnoreCase(documentType)) {
                documentId = documentProcessingService.processPdf(attachment.data(), attachment.filename());
            } else {
                documentId = documentProcessingService.processWithTika(
                        attachment.data(), attachment.filename(), documentType);
            }

            ctx.setDocumentId(documentId);

            List<Document> relevantChunks = fileRagService.findAllByDocumentId(documentId);
            List<String> chunkTexts = relevantChunks.stream()
                    .map(Document::getText)
                    .toList();
            ctx.setExtractedChunks(chunkTexts);

            log.info("FSM extractText: filename={}, documentId={}, chunks={}",
                    attachment.filename(), documentId, chunkTexts.size());

        } catch (DocumentContentNotExtractableException e) {
            boolean isPdf = "pdf".equalsIgnoreCase(documentType);
            if (isPdf) {
                // PDF text extraction failed — FSM will route to vision OCR fallback
                log.info("FSM extractText: PDF text extraction failed for '{}', will fallback to vision OCR: {}",
                        attachment.filename(), e.getMessage());
                ctx.setExtractedChunks(List.of());
            } else {
                // Non-PDF extraction failed — vision OCR is PDF-only, cannot help
                log.warn("FSM extractText: non-PDF extraction failed for '{}' (type={}), no fallback available: {}",
                        attachment.filename(), documentType, e.getMessage());
                ctx.setErrorMessage("Cannot extract text from " + attachment.filename()
                        + " (type: " + documentType + "): " + e.getMessage());
            }
        }
    }

    @Override
    public void runVisionOcr(AttachmentProcessingContext ctx) {
        Attachment attachment = ctx.getAttachment();
        log.info("FSM runVisionOcr: rendering PDF '{}' pages for vision OCR", attachment.filename());

        // Step 1: Render PDF pages to images
        List<Attachment> imageAttachments = renderPdfToImageAttachments(attachment.data(), attachment.filename());
        ctx.setImageAttachments(imageAttachments);

        if (imageAttachments.isEmpty()) {
            log.warn("FSM runVisionOcr: failed to render any pages from PDF '{}'", attachment.filename());
            ctx.setVisionOcrSucceeded(false);
            return;
        }

        // Step 2: Attempt vision OCR extraction
        String extractedText = null;
        try {
            extractedText = extractTextFromImagesViaVision(imageAttachments, attachment.filename());
        } catch (Exception ex) {
            log.warn("FSM runVisionOcr: vision extraction failed for '{}': {}", attachment.filename(), ex.getMessage());
        }

        if (extractedText == null) {
            ctx.setVisionOcrSucceeded(false);
            return;
        }

        // Step 3: OCR succeeded — index extracted text in RAG
        String visionDocId = documentProcessingService.processExtractedText(
                extractedText, attachment.filename());
        if (visionDocId == null) {
            ctx.setVisionOcrSucceeded(false);
            return;
        }

        ctx.setDocumentId(visionDocId);

        List<Document> visionChunks = fileRagService.findAllByDocumentId(visionDocId);
        List<String> chunkTexts = visionChunks.stream()
                .map(Document::getText)
                .toList();
        ctx.setExtractedChunks(chunkTexts);
        ctx.setVisionOcrSucceeded(true);

        log.info("FSM runVisionOcr: OCR succeeded for '{}', documentId={}, chunks={}",
                attachment.filename(), visionDocId, chunkTexts.size());
    }

    @Override
    public void confirmIndexed(AttachmentProcessingContext ctx) {
        // Indexing already happened during extractText or runVisionOcr
        // (DocumentProcessingService.processPdf/processWithTika/processExtractedText
        //  perform extract + chunk + index in one call).
        // This action confirms the pipeline reached RAG_INDEXED state.
        log.info("FSM confirmIndexed: confirmed for '{}', documentId={}, chunks={}",
                ctx.getProcessedFilename(), ctx.getDocumentId(),
                ctx.getExtractedChunks().size());
    }

    @Override
    public void handleUnsupported(AttachmentProcessingContext ctx) {
        Attachment attachment = ctx.getAttachment();
        String mimeType = attachment.mimeType() != null ? attachment.mimeType() : "unknown";
        ctx.setErrorMessage("Unsupported file type: " + mimeType);
        log.warn("FSM handleUnsupported: attachment '{}' has unsupported type: {}",
                attachment.filename(), mimeType);
    }

    // --- Vision OCR helpers (ported from SpringDocumentPreprocessor) ---

    private List<Attachment> renderPdfToImageAttachments(byte[] pdfData, String filename) {
        try (PDDocument document = Loader.loadPDF(pdfData)) {
            PDFRenderer renderer = new PDFRenderer(document);

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

    private String extractTextFromImagesViaVision(List<Attachment> imageAttachments, String filename) {
        List<SpringAIModelConfig> visionCandidates = springAIModelRegistry
                .getCandidatesByCapabilities(Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION), null);
        if (visionCandidates.isEmpty()) {
            log.warn("No VISION-capable model available for text extraction from '{}'", filename);
            return null;
        }

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
