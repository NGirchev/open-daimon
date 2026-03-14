package io.github.ngirchev.opendaimon.it;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Shared test configuration for all integration tests.
 * This class provides a single @SpringBootConfiguration to avoid conflicts
 * between multiple test configurations in the same package hierarchy.
 * 
 * <p>Spring AI auto-configurations are excluded to allow tests to run
 * without real API keys.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "io.github.ngirchev.opendaimon.ai.springai.config.SpringAIAutoConfig",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration",
        "org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration",
})
public class ITTestConfiguration {
}
