package com.example.provisioning.workflow.engine;

import com.example.provisioning.domain.model.StepName;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads {@code source_config.json} at startup and exposes, per request
 * {@code source} (e.g. {@code ETL_JOB}, {@code ADMIN_APP}), the set of
 * {@link StepName}s that caller is allowed to trigger.
 * {@code INITIAL_REQUEST_VALIDATION} is deliberately never listed — it
 * always runs first regardless of source, since it is what validates
 * {@code source} itself.
 *
 * <p>{@code source} is the only gate a step has to clear —
 * {@link com.example.provisioning.workflow.spi.ProvisionStep} has no
 * per-step business condition of its own. A step not selected for a
 * source never gets a row at all (it's seeded based on this set), so
 * it's absent from every response rather than recorded some other
 * status; relative ordering among the steps that do run is preserved.
 *
 * <p>The file is the single source of truth for "which steps a source
 * may trigger": adding a new source, or changing an existing one's step
 * set, is a config edit, not a code change.
 */
@Component
@Slf4j
public class SourceStepConfigProvider {

    private static final String CONFIG_RESOURCE = "source_config.json";

    private final Map<String, Set<StepName>> stepsBySource;

    public SourceStepConfigProvider(ObjectMapper objectMapper) {
        this.stepsBySource = load(objectMapper);
        log.info("Loaded source step profiles: {}", stepsBySource);
    }

    /** True if {@code source} has a configured profile. */
    public boolean isKnownSource(String source) {
        return stepsBySource.containsKey(source);
    }

    /**
     * Steps enabled for the given source. Never null — an unknown
     * source yields an empty set (only {@code INITIAL_REQUEST_VALIDATION},
     * which isn't gated by source, would run — in practice callers
     * reject unknown sources during validation before this is consulted).
     */
    public Set<StepName> stepsFor(String source) {
        return stepsBySource.getOrDefault(source, Set.of());
    }

    /** All configured source names, in file declaration order — used to compose validation error messages. */
    public Set<String> knownSources() {
        return stepsBySource.keySet();
    }

    private static Map<String, Set<StepName>> load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(CONFIG_RESOURCE).getInputStream()) {
            Map<String, Profile> raw =
                objectMapper.readValue(in, new TypeReference<Map<String, Profile>>() { });
            Map<String, Set<StepName>> parsed = new LinkedHashMap<>();
            raw.forEach((source, profile) -> {
                Set<StepName> steps = profile.steps().stream()
                    .map(StepName::valueOf)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
                parsed.put(source, Set.copyOf(steps));
            });
            return Collections.unmodifiableMap(parsed);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException(
                "Failed to load " + CONFIG_RESOURCE + " — provisioning cannot start", e);
        }
    }

    /** Shape of one source entry in {@code source_config.json}. */
    private record Profile(List<String> steps) {
        Profile {
            steps = steps == null ? List.of() : steps;
        }
    }
}
