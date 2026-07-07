package com.example.provisioning.workflow.engine;

import com.example.provisioning.domain.model.OrgType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads {@code default_config.json} at startup and exposes, per
 * {@link OrgType}, the set of configuration sections that org tier
 * receives. Steps read these sections (via
 * {@link com.example.provisioning.workflow.spi.ProvisionContext}) to
 * decide whether they apply — a missing section means the step is
 * skipped for that org type.
 *
 * <p>The file is the single source of truth for "which steps apply to
 * which tier": adding a tier or toggling a section is a config edit,
 * not a code change.
 */
@Component
@Slf4j
public class DefaultConfigProvider {

    private static final String CONFIG_RESOURCE = "default_config.json";

    private final Map<OrgType, Set<String>> sectionsByType;

    public DefaultConfigProvider(ObjectMapper objectMapper) {
        this.sectionsByType = load(objectMapper);
        log.info("Loaded default config profiles: {}", sectionsByType);
    }

    /**
     * Sections enabled for the given org type. Never null — an unknown
     * or unconfigured type yields an empty set (every conditional step
     * skips).
     */
    public Set<String> sectionsFor(OrgType orgType) {
        return sectionsByType.getOrDefault(orgType, Set.of());
    }

    private static Map<OrgType, Set<String>> load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(CONFIG_RESOURCE).getInputStream()) {
            Map<String, Profile> raw =
                objectMapper.readValue(in, new TypeReference<Map<String, Profile>>() { });
            Map<OrgType, Set<String>> parsed = new EnumMap<>(OrgType.class);
            raw.forEach((type, profile) ->
                parsed.put(OrgType.valueOf(type), Set.copyOf(profile.sections())));
            return parsed;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException(
                "Failed to load " + CONFIG_RESOURCE + " — provisioning cannot start", e);
        }
    }

    /** Shape of one org-type entry in {@code default_config.json}. */
    private record Profile(List<String> sections) {
        Profile {
            sections = sections == null ? List.of() : sections;
        }
    }
}
