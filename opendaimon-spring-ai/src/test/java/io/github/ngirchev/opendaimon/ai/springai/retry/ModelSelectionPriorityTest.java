package io.github.ngirchev.opendaimon.ai.springai.retry;

import io.github.ngirchev.opendaimon.ai.springai.config.SpringAIModelConfig;
import io.github.ngirchev.opendaimon.common.ai.ModelCapabilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that model selection sorts by priority first, then by capability depth.
 *
 * <p>Models:
 * <ul>
 *   <li>ONE  {priority=1, caps=[A, B]}</li>
 *   <li>TWO  {priority=2, caps=[C, B]}</li>
 *   <li>THREE{priority=2, caps=[A, C]}</li>
 * </ul>
 *
 * <p>Expected selection:
 * <ul>
 *   <li>request {A, B} -> ONE (has both, best priority)</li>
 *   <li>request {A}    -> ONE (has A, best priority)</li>
 *   <li>request {B}    -> ONE (has B, best priority)</li>
 *   <li>request {C}    -> TWO (same priority as THREE, but C is at index 0 vs 1)</li>
 * </ul>
 */
class ModelSelectionPriorityTest {

    private SpringAIModelRegistry registry;

    @BeforeEach
    void setUp() {
        SpringAIModelConfig one = model("ONE", 1, "CHAT", "VISION");
        SpringAIModelConfig two = model("TWO", 2, "WEB", "VISION");
        SpringAIModelConfig three = model("THREE", 2, "CHAT", "WEB");

        OpenRouterModelsProperties props = new OpenRouterModelsProperties();
        registry = new SpringAIModelRegistry(List.of(one, two, three), null, props);
    }

    @Test
    @DisplayName("request {CHAT, VISION} -> ONE (has both, priority 1)")
    void requestBothCaps_selectsOne() {
        List<SpringAIModelConfig> candidates = registry.getCandidatesByCapabilities(
                Set.of(ModelCapabilities.CHAT, ModelCapabilities.VISION), null);

        assertThat(candidates).extracting(SpringAIModelConfig::getName)
                .first().isEqualTo("ONE");
    }

    @Test
    @DisplayName("request {CHAT} -> ONE (priority 1 beats priority 2)")
    void requestChatOnly_selectsOneByPriority() {
        List<SpringAIModelConfig> candidates = registry.getCandidatesByCapabilities(
                Set.of(ModelCapabilities.CHAT), null);

        assertThat(candidates).extracting(SpringAIModelConfig::getName)
                .first().isEqualTo("ONE");
    }

    @Test
    @DisplayName("request {VISION} -> ONE (priority 1 beats priority 2)")
    void requestVisionOnly_selectsOneByPriority() {
        List<SpringAIModelConfig> candidates = registry.getCandidatesByCapabilities(
                Set.of(ModelCapabilities.VISION), null);

        assertThat(candidates).extracting(SpringAIModelConfig::getName)
                .first().isEqualTo("ONE");
    }

    @Test
    @DisplayName("request {WEB} -> TWO (same priority as THREE, but WEB at lower index)")
    void requestWeb_selectsTwoByCapabilityDepth() {
        List<SpringAIModelConfig> candidates = registry.getCandidatesByCapabilities(
                Set.of(ModelCapabilities.WEB), null);

        assertThat(candidates).extracting(SpringAIModelConfig::getName)
                .first().isEqualTo("TWO");
    }

    @Test
    @DisplayName("model with extra capabilities is NOT excluded for simpler requests")
    void extraCapsDoNotExcludeModel() {
        // ONE has [CHAT, VISION] but request only {CHAT} — ONE must still be selected
        List<SpringAIModelConfig> candidates = registry.getCandidatesByCapabilities(
                Set.of(ModelCapabilities.CHAT), null);

        assertThat(candidates).extracting(SpringAIModelConfig::getName)
                .contains("ONE");
    }

    private static SpringAIModelConfig model(String name, int priority, String... caps) {
        SpringAIModelConfig config = new SpringAIModelConfig();
        config.setName(name);
        config.setPriority(priority);
        config.setProviderType(SpringAIModelConfig.ProviderType.OPENAI);
        Set<ModelCapabilities> capSet = new LinkedHashSet<>();
        for (String cap : caps) {
            capSet.add(ModelCapabilities.valueOf(cap));
        }
        config.setCapabilities(capSet);
        return config;
    }
}
