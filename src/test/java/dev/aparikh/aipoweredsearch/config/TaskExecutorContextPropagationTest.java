package dev.aparikh.aipoweredsearch.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W5: with {@code spring.task.execution.propagate-context=true}, an observation that is current
 * when work is submitted to {@code applicationTaskExecutor} is current on the executor's thread
 * too. That is what makes the {@code rag.retrieve} spans nest under the {@code /ask} request,
 * since {@code RetrievalAugmentationAdvisor} retrieves on that executor.
 */
class TaskExecutorContextPropagationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
            .withPropertyValues("spring.threads.virtual.enabled=true");

    @Test
    void observationOpenedOnTheCallerIsCurrentOnTheExecutorThread() {
        contextRunner.withPropertyValues("spring.task.execution.propagate-context=true")
                .run(context -> assertThat(currentObservationOnExecutor(
                        context.getBean("applicationTaskExecutor", TaskExecutor.class)))
                        .isEqualTo("ask"));
    }

    @Test
    void withoutThePropertyTheObservationDoesNotFollow() {
        contextRunner.run(context -> assertThat(currentObservationOnExecutor(
                context.getBean("applicationTaskExecutor", TaskExecutor.class)))
                .isEqualTo("none"));
    }

    private static String currentObservationOnExecutor(TaskExecutor executor) throws Exception {
        TestObservationRegistry registry = TestObservationRegistry.create();
        Observation parent = Observation.start("ask", registry);
        try (Observation.Scope _ = parent.openScope()) {
            return CompletableFuture.supplyAsync(() -> {
                Observation current = registry.getCurrentObservation();
                return current == null ? "none" : current.getContext().getName();
            }, executor).get(5, TimeUnit.SECONDS);
        } finally {
            parent.stop();
        }
    }
}
