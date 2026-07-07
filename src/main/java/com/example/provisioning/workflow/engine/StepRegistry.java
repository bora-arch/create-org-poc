package com.example.provisioning.workflow.engine;

import com.example.provisioning.workflow.spi.ProvisionStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers every {@link ProvisionStep} bean, sorts them by declared
 * order, and enforces uniqueness at startup. A duplicate order is
 * treated as a wiring bug — the app fails fast rather than executing
 * steps in nondeterministic order.
 */
@Component
@Slf4j
public class StepRegistry {

    private final List<ProvisionStep> ordered;

    public StepRegistry(List<ProvisionStep> steps) {
        this.ordered = validateAndSort(steps);
        log.info("Registered {} provisioning steps: {}", ordered.size(),
            ordered.stream().map(s -> s.name() + "@" + s.order()).toList());
    }

    public List<ProvisionStep> ordered() {
        return ordered;
    }

    public int total() {
        return ordered.size();
    }

    private static List<ProvisionStep> validateAndSort(List<ProvisionStep> steps) {
        Map<Integer, ProvisionStep> byOrder = new HashMap<>();
        for (ProvisionStep step : steps) {
            ProvisionStep clash = byOrder.putIfAbsent(step.order(), step);
            if (clash != null) {
                throw new IllegalStateException(
                    "Duplicate provisioning step order " + step.order()
                        + ": " + clash.name() + " vs " + step.name());
            }
        }
        return steps.stream()
            .sorted(Comparator.comparingInt(ProvisionStep::order))
            .toList();
    }
}
