package com.example.provisioning.api;

import com.example.provisioning.domain.model.StepStatus;
import com.example.provisioning.external.ExternalCallException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    @Autowired FlakyOncePrmPreferencesClient client;

    // "STANDARD" (the default org type) enables branding, rfs_ui, citations,
    // and license — so ASSIGN_FSP_RECOMMENDATION_MODELS, DISABLE_PRM_LICENSES,
    // and SETUP_FSP_BOOSTERS are skipped. Uses source=DEFAULT, which selects
    // every step, so only org_type gating is under test here.
    private static final Set<String> STANDARD_SKIPPED = Set.of(
        "ASSIGN_FSP_RECOMMENDATION_MODELS", "DISABLE_PRM_LICENSES", "SETUP_FSP_BOOSTERS");

    // internal enables no optional sections: everything conditional is
    // skipped except DISABLE_PRM_LICENSES (license section is absent).
    private static final Set<String> INTERNAL_SKIPPED = Set.of(
        "ASSIGN_FSP_RECOMMENDATION_MODELS", "SETUP_DEFAULT_BRANDING_PRM_PREFERENCES",
        "SETUP_DEFAULT_RFS_UI_PRM_PREFERENCES", "SETUP_DEFAULT_CITATIONS",
        "ENABLE_PRM_LICENSES", "SETUP_FSP_BOOSTERS");

    @Test
    void standardOrgType_skipsRecommendationDisableAndBoosters() throws Exception {
        JsonNode accepted = postOrganization(
            requestJson(newOrgUid(), "STANDARD", "DEFAULT", "sav20006@gmail.com", "ext-1"));
        UUID jobId = UUID.fromString(accepted.get("jobId").asText());

        awaitSuccessWithSkips(jobId, STANDARD_SKIPPED);
    }

    @Test
    void internalOrgType_skipsAllExternalSectionsAndRunsDisableLicenses() throws Exception {
        JsonNode accepted = postOrganization(
            requestJson(newOrgUid(), "INTERNAL", "DEFAULT", "sav20006@gmail.com", "ext-2"));
        UUID jobId = UUID.fromString(accepted.get("jobId").asText());

        awaitSuccessWithSkips(jobId, INTERNAL_SKIPPED);
    }

    @Test
    void enterpriseOrgType_runsAllExceptDisableLicenses() throws Exception {
        JsonNode accepted = postOrganization(
            requestJson(newOrgUid(), "ENTERPRISE", "DEFAULT", "sav20006@gmail.com", "ext-3"));
        UUID jobId = UUID.fromString(accepted.get("jobId").asText());

        awaitSuccessWithSkips(jobId, Set.of("DISABLE_PRM_LICENSES"));
    }

    @Test
    void etlJobSource_stepListContainsOnlyValidationCreateOrgAndEnableLicenses() throws Exception {
        JsonNode accepted = postOrganization(
            requestJson(newOrgUid(), "STANDARD", "ETL_JOB", "sav20006@gmail.com", "ext-etl"));
        UUID jobId = UUID.fromString(accepted.get("jobId").asText());

        JsonNode state = awaitStatus(jobId, "SUCCESS");
        // Steps not configured for this source have no row at all — they
        // are absent from the list entirely, not reported SKIPPED.
        assertThat(state.get("steps")).hasSize(3);
        assertThat(state.get("progress").asInt()).isEqualTo(3);
        assertThat(state.get("totalSteps").asInt()).isEqualTo(3);
        assertThat(findStep(state, "INITIAL_REQUEST_VALIDATION").get("status").asText()).isEqualTo("SUCCESS");
        assertThat(findStep(state, "CREATE_ORG_IN_FSP").get("status").asText()).isEqualTo("SUCCESS");
        assertThat(findStep(state, "ENABLE_PRM_LICENSES").get("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void adminAppSource_stepListContainsOnlyValidationCreateOrgAndVocabularies() throws Exception {
        JsonNode accepted = postOrganization(
            requestJson(newOrgUid(), "STANDARD", "ADMIN_APP", "sav20006@gmail.com", "ext-admin"));
        UUID jobId = UUID.fromString(accepted.get("jobId").asText());

        JsonNode state = awaitStatus(jobId, "SUCCESS");
        assertThat(state.get("steps")).hasSize(3);
        assertThat(state.get("progress").asInt()).isEqualTo(3);
        assertThat(state.get("totalSteps").asInt()).isEqualTo(3);
        assertThat(findStep(state, "INITIAL_REQUEST_VALIDATION").get("status").asText()).isEqualTo("SUCCESS");
        assertThat(findStep(state, "CREATE_ORG_IN_FSP").get("status").asText()).isEqualTo("SUCCESS");
        assertThat(findStep(state, "SETUP_DEFAULT_VOCABULARIES_IN_CE").get("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void etlJobSource_preservesCatalogOrderAmongSelectedSteps() throws Exception {
        JsonNode accepted = postOrganization(
            requestJson(newOrgUid(), "STANDARD", "ETL_JOB", "sav20006@gmail.com", "ext-order"));
        UUID jobId = UUID.fromString(accepted.get("jobId").asText());

        JsonNode state = awaitStatus(jobId, "SUCCESS");
        // ENABLE_PRM_LICENSES (catalog order 100) must still appear after
        // CREATE_ORG_IN_FSP (order 10), and only these three steps at all —
        // source restricts the set, it does not reorder it, and steps
        // outside the set aren't merely skipped, they're absent.
        java.util.List<String> names = new java.util.ArrayList<>();
        state.get("steps").forEach(s -> names.add(s.get("name").asText()));
        assertThat(names).containsExactly("INITIAL_REQUEST_VALIDATION", "CREATE_ORG_IN_FSP", "ENABLE_PRM_LICENSES");
    }

    @Test
    void invalidOrgUid_isRejectedSynchronouslyWithFirstStepFailed() throws Exception {
        MvcResult result = mockMvc.perform(post("/organizations")
                .contentType(APPLICATION_JSON)
                .content(requestJson("not-a-uuid", "STANDARD", "DEFAULT", "sav20006@gmail.com", "ext-4")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.externalJobUid").value("ext-4"))
            .andExpect(jsonPath("$.steps[0].name").value("INITIAL_REQUEST_VALIDATION"))
            .andExpect(jsonPath("$.steps[0].status").value("FAILED"))
            .andExpect(jsonPath("$.steps[0].errorCode").value("VALIDATION_FAILED"))
            .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        // source was never resolved (validation failed before it could be),
        // so no other step rows exist at all — not even NOT_STARTED ones.
        assertThat(body.get("steps")).hasSize(1);
        assertThat(body.get("progress").asInt()).isEqualTo(1);
        assertThat(body.get("totalSteps").asInt()).isEqualTo(1);
        assertThat(body.get("steps").get(0).get("errorMessage").asText())
            .contains("org_uid must be a valid UUID");
    }

    @Test
    void invalidOrgType_isRejectedSynchronously() throws Exception {
        mockMvc.perform(post("/organizations")
                .contentType(APPLICATION_JSON)
                .content(requestJson(newOrgUid(), "bogus-tier", "DEFAULT", "sav20006@gmail.com", "ext-5")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.steps[0].errorMessage").value(
                org.hamcrest.Matchers.containsString("org_type must be one of")));
    }

    @Test
    void invalidSource_isRejectedSynchronously() throws Exception {
        mockMvc.perform(post("/organizations")
                .contentType(APPLICATION_JSON)
                .content(requestJson(newOrgUid(), "STANDARD", "BOGUS_SOURCE", "sav20006@gmail.com", "ext-6")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.steps[0].errorMessage").value(
                org.hamcrest.Matchers.containsString("source must be one of")));
    }

    @Test
    void missingJobReturns404() throws Exception {
        mockMvc.perform(get("/organization-provision-jobs/{jobId}", UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void missingFieldReturns400WithFieldViolations() throws Exception {
        mockMvc.perform(post("/organizations")
                .contentType(APPLICATION_JSON)
                .content("{\"org_uid\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void retryWithSameOrgUid_resumesFromFailedStepInsteadOfRestarting() throws Exception {
        client.armFailureOnce();
        String orgUid = newOrgUid();
        String body = requestJson(orgUid, "STANDARD", "DEFAULT", "sav20006@gmail.com", "ext-retry");

        JsonNode firstResponse = postOrganization(body);
        UUID jobId = UUID.fromString(firstResponse.get("jobId").asText());

        JsonNode failedState = awaitStatus(jobId, "FAILED");
        assertThat(findStep(failedState, "CREATE_ORG_IN_FSP").get("status").asText()).isEqualTo("SUCCESS");
        assertThat(findStep(failedState, "SETUP_DEFAULT_PRM_PREFERENCES").get("status").asText())
            .isEqualTo("FAILED");
        assertThat(findStep(failedState, "SETUP_DEFAULT_RFS_UI_PRM_PREFERENCES").get("status").asText())
            .isEqualTo("NOT_STARTED");
        assertThat(client.createOrgInvocations.get()).isEqualTo(1);

        // Retry: same org_uid resumes the same job at the failed step.
        JsonNode retryResponse = postOrganization(body);
        assertThat(retryResponse.get("jobId").asText()).isEqualTo(jobId.toString());

        JsonNode successState = awaitStatus(jobId, "SUCCESS");
        assertThat(successState.get("progress").asInt()).isEqualTo(13);
        assertThat(successState.get("totalSteps").asInt()).isEqualTo(13);

        // CREATE_ORG_IN_FSP already succeeded before the failure — must not re-run on retry.
        assertThat(client.createOrgInvocations.get()).isEqualTo(1);
        assertThat(client.prmPreferencesInvocations.get()).isEqualTo(2);
    }

    private JsonNode postOrganization(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/organizations")
                .contentType(APPLICATION_JSON)
                .content(body))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").exists())
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void awaitSuccessWithSkips(UUID jobId, Set<String> skipped) {
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() -> {
                JsonNode state = fetchJob(jobId);
                assertThat(state.get("status").asText()).isEqualTo("SUCCESS");
                assertThat(state.get("progress").asInt()).isEqualTo(13);
                assertThat(state.get("totalSteps").asInt()).isEqualTo(13);
                assertThat(state.get("steps")).hasSize(13);
                state.get("steps").forEach(s -> {
                    String name = s.get("name").asText();
                    if (name.equals("INITIAL_REQUEST_VALIDATION")) {
                        assertThat(s.get("status").asText()).isEqualTo("SUCCESS");
                        return;
                    }
                    String expected = skipped.contains(name)
                        ? StepStatus.SKIPPED.name()
                        : StepStatus.SUCCESS.name();
                    assertThat(s.get("status").asText()).as("step %s", name).isEqualTo(expected);
                });
            });
    }

    private JsonNode awaitStatus(UUID jobId, String status) {
        return Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(100))
            .until(() -> fetchJob(jobId), state -> state.get("status").asText().equals(status));
    }

    private JsonNode findStep(JsonNode state, String name) {
        for (JsonNode step : state.get("steps")) {
            if (step.get("name").asText().equals(name)) {
                return step;
            }
        }
        throw new AssertionError("No step named " + name);
    }

    private JsonNode fetchJob(UUID jobId) throws Exception {
        MvcResult poll = mockMvc.perform(get("/organization-provision-jobs/" + jobId))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(poll.getResponse().getContentAsString());
    }

    private static String newOrgUid() {
        return UUID.randomUUID().toString();
    }

    private static String requestJson(String orgUid, String orgType, String source,
                                      String serviceUserAccount, String externalJobUid) {
        return """
            {"org_uid":"%s","org_type":"%s","source":"%s","service_user_account":"%s","external_job_uid":"%s"}
            """.formatted(orgUid, orgType, source, serviceUserAccount, externalJobUid);
    }

    @TestConfiguration
    static class DeterministicClientConfig {

        @Bean
        @Primary
        FlakyOncePrmPreferencesClient flakyOnceClient() {
            return new FlakyOncePrmPreferencesClient();
        }
    }

    /**
     * Deterministic client: every call succeeds except the first
     * invocation of {@code setupDefaultPrmPreferences}, which fails
     * once (simulating a transient downstream error) so the retry/resume
     * behaviour can be exercised without relying on the mock's random
     * failures.
     */
    static class FlakyOncePrmPreferencesClient implements ExternalOrganizationClient {

        final AtomicInteger createOrgInvocations = new AtomicInteger();
        final AtomicInteger prmPreferencesInvocations = new AtomicInteger();
        private final AtomicBoolean failPrmPreferencesOnce = new AtomicBoolean(false);

        /**
         * Defaults to never failing, since this client is shared (as the
         * {@code @Primary} bean) across every test in the class. Only the
         * retry test arms the one-shot failure right before it needs it,
         * so the other tests are unaffected regardless of execution order.
         */
        void armFailureOnce() {
            createOrgInvocations.set(0);
            prmPreferencesInvocations.set(0);
            failPrmPreferencesOnce.set(true);
        }

        @Override public void createOrgInFsp(UUID orgUid) { createOrgInvocations.incrementAndGet(); }
        @Override public void setupOrgInFsp(UUID organizationId) { }
        @Override public void assignFspRecommendationModels(UUID organizationId) { }
        @Override public void setupDefaultBrandingPrmPreferences(UUID organizationId) { }

        @Override
        public void setupDefaultPrmPreferences(UUID organizationId) {
            prmPreferencesInvocations.incrementAndGet();
            if (failPrmPreferencesOnce.compareAndSet(true, false)) {
                throw new ExternalCallException("500", "Simulated transient failure");
            }
        }

        @Override public void setupDefaultRfsUiPrmPreferences(UUID organizationId) { }
        @Override public void setupDefaultVocabulariesInCe(UUID organizationId) { }
        @Override public void setupDefaultDatasourcesInFsp(UUID organizationId) { }
        @Override public void setupDefaultCitations(UUID organizationId) { }
        @Override public void enablePrmLicenses(UUID organizationId) { }
        @Override public void disablePrmLicenses(UUID organizationId) { }
        @Override public void setupFspBoosters(UUID organizationId) { }
    }
}
