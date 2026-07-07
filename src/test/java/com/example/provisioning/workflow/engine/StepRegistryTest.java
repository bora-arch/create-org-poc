package com.example.provisioning.workflow.engine;

import com.example.provisioning.domain.model.StepName;
import com.example.provisioning.workflow.spi.ProvisionContext;
import com.example.provisioning.workflow.spi.ProvisionStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepRegistryTest {

    @Test
    void ordersStepsByDeclaredOrder() {
        StepRegistry registry = new StepRegistry(List.of(
            step(StepName.SETUP_ORG, 20),
            step(StepName.CREATE_ORG, 10),
            step(StepName.UPLOAD_LOGO, 30)
        ));

        assertThat(registry.ordered())
            .extracting(ProvisionStep::name)
            .containsExactly(StepName.CREATE_ORG, StepName.SETUP_ORG, StepName.UPLOAD_LOGO);
        assertThat(registry.total()).isEqualTo(3);
    }

    @Test
    void failsFastOnDuplicateOrder() {
        List<ProvisionStep> steps = List.of(
            step(StepName.CREATE_ORG, 10),
            step(StepName.SETUP_ORG, 10)
        );

        assertThatThrownBy(() -> new StepRegistry(steps))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate provisioning step order 10");
    }

    private static ProvisionStep step(StepName name, int order) {
        return new ProvisionStep() {
            @Override public StepName name() { return name; }
            @Override public int order() { return order; }
            @Override public void execute(ProvisionContext context) { }
        };
    }
}
