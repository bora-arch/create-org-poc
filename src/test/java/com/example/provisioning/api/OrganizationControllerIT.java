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

    @Test
    void postAcceptedThenJobRunsToSuccess_disableStepSkippedByDefault() throws Exception {
        MvcResult result = mockMvc.perform(post("/organizations")
                .contentType(APPLICATION_JSON)
                .content("""
                    {"name":"Acme","createdBy":"sav20006@gmail.com"}
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").exists())
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
            .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID jobId = UUID.fromString(body.get("jobId").asText());

        // prmLicensesEnabled defaults to true: ENABLE_PRM_LICENSES runs,
        // DISABLE_PRM_LICENSES is skipped by business rule.
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
                    String expected = "DISABLE_PRM_LICENSES".equals(s.get("name").asText())
                        ? StepStatus.SKIPPED.name()
                        : StepStatus.SUCCESS.name();
                    assertThat(s.get("status").asText()).isEqualTo(expected);
                });
            });
    }

    @Test
    void prmLicensesDisabled_skipsEnableStepAndRunsDisableStep() throws Exception {
        MvcResult result = mockMvc.perform(post("/organizations")
                .contentType(APPLICATION_JSON)
                .content("""
                    {"name":"Acme","createdBy":"sav20006@gmail.com","prmLicensesEnabled":false}
                    """))
            .andExpect(status().isAccepted())
            .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID jobId = UUID.fromString(body.get("jobId").asText());

        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() -> {
                JsonNode state = fetchJob(jobId);
                assertThat(state.get("status").asText()).isEqualTo("SUCCESS");
                assertThat(state.get("progress").asInt()).isEqualTo(12);
                state.get("steps").forEach(s -> {
                    String expected = "ENABLE_PRM_LICENSES".equals(s.get("name").asText())
                        ? StepStatus.SKIPPED.name()
                        : StepStatus.SUCCESS.name();
                    assertThat(s.get("status").asText()).isEqualTo(expected);
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
