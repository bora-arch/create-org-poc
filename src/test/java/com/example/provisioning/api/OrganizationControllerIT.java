package com.example.provisioning.api;

import com.example.provisioning.domain.model.StepStatus;
import com.example.provisioning.external.ExternalOrganizationClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(OrganizationControllerIT.DeterministicClientConfig.class)
class OrganizationControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    // STANDARD (the default org type) enables branding, rfs_ui, citations,
    // and license — so ASSIGN_FSP_RECOMMENDATION_MODELS, DISABLE_PRM_LICENSES,
    // and SETUP_FSP_BOOSTERS are skipped.
    private static final Set<String> STANDARD_SKIPPED = Set.of(
        "ASSIGN_FSP_RECOMMENDATION_MODELS", "DISABLE_PRM_LICENSES", "SETUP_FSP_BOOSTERS");

    // INTERNAL enables no optional sections: everything conditional is
    // skipped except DISABLE_PRM_LICENSES (license section is absent).
    private static final Set<String> INTERNAL_SKIPPED = Set.of(
        "ASSIGN_FSP_RECOMMENDATION_MODELS", "SETUP_DEFAULT_BRANDING_PRM_PREFERENCES",
        "SETUP_DEFAULT_RFS_UI_PRM_PREFERENCES", "SETUP_DEFAULT_CITATIONS",
        "ENABLE_PRM_LICENSES", "SETUP_FSP_BOOSTERS");

    @Test
    void standardOrgTypeByDefault_skipsRecommendationDisableAndBoosters() throws Exception {
        UUID jobId = postOrganization("""
            {"name":"Acme","createdBy":"sav20006@gmail.com"}
            """);

        awaitSuccessWithSkips(jobId, STANDARD_SKIPPED);
    }

    @Test
    void internalOrgType_skipsAllExternalSectionsAndRunsDisableLicenses() throws Exception {
        UUID jobId = postOrganization("""
            {"name":"Acme","createdBy":"sav20006@gmail.com","orgType":"INTERNAL"}
            """);

        awaitSuccessWithSkips(jobId, INTERNAL_SKIPPED);
    }

    @Test
    void enterpriseOrgType_runsAllExceptDisableLicenses() throws Exception {
        UUID jobId = postOrganization("""
            {"name":"Acme","createdBy":"sav20006@gmail.com","orgType":"ENTERPRISE"}
            """);

        awaitSuccessWithSkips(jobId, Set.of("DISABLE_PRM_LICENSES"));
    }

    private UUID postOrganization(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/organizations")
                .contentType(APPLICATION_JSON)
                .content(body))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").exists())
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
            .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(json.get("jobId").asText());
    }

    private void awaitSuccessWithSkips(UUID jobId, Set<String> skipped) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() -> {
                JsonNode state = fetchJob(jobId);
                assertThat(state.get("status").asText()).isEqualTo("SUCCESS");
                assertThat(state.get("progress").asInt()).isEqualTo(12);
                assertThat(state.get("totalSteps").asInt()).isEqualTo(12);
                assertThat(state.get("steps")).hasSize(12);
                state.get("steps").forEach(s -> {
                    String expected = skipped.contains(s.get("name").asText())
                        ? StepStatus.SKIPPED.name()
                        : StepStatus.SUCCESS.name();
                    assertThat(s.get("status").asText())
                        .as("step %s", s.get("name").asText())
                        .isEqualTo(expected);
                });
            });
    }

    private JsonNode fetchJob(UUID jobId) throws Exception {
        MvcResult poll = mockMvc.perform(get("/organization-provision-jobs/" + jobId))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(poll.getResponse().getContentAsString());
    }

    @Test
    void missingJobReturns404() throws Exception {
        mockMvc.perform(get("/organization-provision-jobs/{jobId}", UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void invalidRequestReturns400WithFieldViolations() throws Exception {
        mockMvc.perform(post("/organizations")
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.violations[0].field").value("name"));
    }

    @TestConfiguration
    static class DeterministicClientConfig {

        @Bean
        @Primary
        ExternalOrganizationClient alwaysSucceedsClient() {
            return new ExternalOrganizationClient() {
                @Override public UUID createOrgInFsp(String name) { return UUID.randomUUID(); }
                @Override public void setupOrgInFsp(UUID organizationId) { }
                @Override public void assignFspRecommendationModels(UUID organizationId) { }
                @Override public void setupDefaultBrandingPrmPreferences(UUID organizationId) { }
                @Override public void setupDefaultPrmPreferences(UUID organizationId) { }
                @Override public void setupDefaultRfsUiPrmPreferences(UUID organizationId) { }
                @Override public void setupDefaultVocabulariesInCe(UUID organizationId) { }
                @Override public void setupDefaultDatasourcesInFsp(UUID organizationId) { }
                @Override public void setupDefaultCitations(UUID organizationId) { }
                @Override public void enablePrmLicenses(UUID organizationId) { }
                @Override public void disablePrmLicenses(UUID organizationId) { }
                @Override public void setupFspBoosters(UUID organizationId) { }
            };
        }
    }

}
