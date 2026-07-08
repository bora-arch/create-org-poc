package com.example.provisioning.api;

import com.example.provisioning.external.ExternalCallException;
import com.example.provisioning.external.ExternalOrganizationClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chained execution / continue-on-failure behaviour.
 *
 * <p>Uses a client that fails one named downstream call deterministically
 * so we can assert the two distinct outcomes: a non-critical failure lets
 * the chain finish {@code COMPLETED_WITH_ERRORS}, while a critical failure
 * ({@code CREATE_ORG_IN_FSP}) halts the chain {@code FAILED}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ContinueOnFailureIT.FailingClientConfig.class)
class ContinueOnFailureIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired FailOneCallClient client;

    @BeforeEach
    void resetClient() {
        client.reset();
    }

    @Test
    void nonCriticalFailure_continuesChainAndCompletesWithErrors() throws Exception {
        // setupDefaultBrandingPrmPreferences (order 40) is non-critical and runs
        // for STANDARD. A later step, setupDefaultCitations (order 90), must still run.
        client.failOn("setupDefaultBrandingPrmPreferences", "503", "Service Unavailable");

        UUID jobId = postOrganization("""
            {"name":"Acme","createdBy":"sav20006@gmail.com"}
            """);

        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .untilAsserted(() -> {
                JsonNode state = fetchJob(jobId);
                assertThat(state.get("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
                assertThat(state.get("failedSteps").asInt()).isEqualTo(1);
                assertThat(state.get("progress").asInt()).isEqualTo(12);

                assertThat(statusOf(state, "SETUP_DEFAULT_BRANDING_PRM_PREFERENCES")).isEqualTo("FAILED");
                assertThat(errorCodeOf(state, "SETUP_DEFAULT_BRANDING_PRM_PREFERENCES")).isEqualTo("503");
                // A step ordered after the failed one still ran — the chain did not stop.
                assertThat(statusOf(state, "SETUP_DEFAULT_CITATIONS")).isEqualTo("SUCCESS");
                // No step is left NOT_STARTED.
                state.get("steps").forEach(s ->
                    assertThat(s.get("status").asText()).isNotEqualTo("NOT_STARTED"));
            });
    }

    @Test
    void criticalFailure_haltsChainAndFailsJob() throws Exception {
        // createOrgInFsp (order 10) is critical: its failure halts the chain.
        client.failOn("createOrgInFsp", "500", "Internal Server Error");

        UUID jobId = postOrganization("""
            {"name":"Acme","createdBy":"sav20006@gmail.com"}
            """);

        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .untilAsserted(() -> {
                JsonNode state = fetchJob(jobId);
                assertThat(state.get("status").asText()).isEqualTo("FAILED");
                assertThat(state.get("failedSteps").asInt()).isEqualTo(1);
                assertThat(state.get("currentStep").asText()).isEqualTo("CREATE_ORG_IN_FSP");

                assertThat(statusOf(state, "CREATE_ORG_IN_FSP")).isEqualTo("FAILED");
                // Everything after the critical step is left untouched.
                assertThat(statusOf(state, "SETUP_ORG_IN_FSP")).isEqualTo("NOT_STARTED");
                assertThat(statusOf(state, "SETUP_DEFAULT_PRM_PREFERENCES")).isEqualTo("NOT_STARTED");
            });
    }

    private String statusOf(JsonNode state, String stepName) {
        return stepNode(state, stepName).get("status").asText();
    }

    private String errorCodeOf(JsonNode state, String stepName) {
        return stepNode(state, stepName).get("errorCode").asText();
    }

    private JsonNode stepNode(JsonNode state, String stepName) {
        for (JsonNode s : state.get("steps")) {
            if (s.get("name").asText().equals(stepName)) {
                return s;
            }
        }
        throw new AssertionError("step not found: " + stepName);
    }

    private UUID postOrganization(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/organizations")
                .contentType(APPLICATION_JSON)
                .content(body))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").exists())
            .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(json.get("jobId").asText());
    }

    private JsonNode fetchJob(UUID jobId) throws Exception {
        MvcResult poll = mockMvc.perform(get("/organization-provision-jobs/" + jobId))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(poll.getResponse().getContentAsString());
    }

    @TestConfiguration
    static class FailingClientConfig {
        @Bean
        @Primary
        FailOneCallClient failOneCallClient() {
            return new FailOneCallClient();
        }
    }

    /**
     * Succeeds every call except the one named via {@link #failOn}, which
     * throws {@link ExternalCallException}. Deterministic, so the two
     * outcomes are reproducible (unlike the random production mock).
     */
    static class FailOneCallClient implements ExternalOrganizationClient {

        private final Map<String, ExternalCallException> failures = new HashMap<>();

        void reset() {
            failures.clear();
        }

        void failOn(String call, String code, String message) {
            failures.put(call, new ExternalCallException(code, message));
        }

        private void maybeFail(String call) {
            ExternalCallException ex = failures.get(call);
            if (ex != null) {
                throw new ExternalCallException(ex.getErrorCode(), ex.getMessage());
            }
        }

        @Override public UUID createOrgInFsp(String name) { maybeFail("createOrgInFsp"); return UUID.randomUUID(); }
        @Override public void setupOrgInFsp(UUID id) { maybeFail("setupOrgInFsp"); }
        @Override public void assignFspRecommendationModels(UUID id) { maybeFail("assignFspRecommendationModels"); }
        @Override public void setupDefaultBrandingPrmPreferences(UUID id) { maybeFail("setupDefaultBrandingPrmPreferences"); }
        @Override public void setupDefaultPrmPreferences(UUID id) { maybeFail("setupDefaultPrmPreferences"); }
        @Override public void setupDefaultRfsUiPrmPreferences(UUID id) { maybeFail("setupDefaultRfsUiPrmPreferences"); }
        @Override public void setupDefaultVocabulariesInCe(UUID id) { maybeFail("setupDefaultVocabulariesInCe"); }
        @Override public void setupDefaultDatasourcesInFsp(UUID id) { maybeFail("setupDefaultDatasourcesInFsp"); }
        @Override public void setupDefaultCitations(UUID id) { maybeFail("setupDefaultCitations"); }
        @Override public void enablePrmLicenses(UUID id) { maybeFail("enablePrmLicenses"); }
        @Override public void disablePrmLicenses(UUID id) { maybeFail("disablePrmLicenses"); }
        @Override public void setupFspBoosters(UUID id) { maybeFail("setupFspBoosters"); }
    }
}
