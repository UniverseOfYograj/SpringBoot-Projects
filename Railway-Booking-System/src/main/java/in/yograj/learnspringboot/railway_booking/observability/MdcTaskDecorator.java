package in.yograj.learnspringboot.railway_booking.observability;

import org.slf4j.MDC;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Propagates SLF4J MDC context (including correlationId) from caller threads
 * to asynchronous task execution threads.
 *
 * Guarantees:
 * 1. Safe context propagation: snapshots caller thread's MDC context at decoration/submission time.
 * 2. Restores previous context: captures worker thread's existing MDC before execution
 *    and restores it in a finally block to prevent ThreadLocal pollution.
 * 3. Cleans up when no context: if caller thread has no MDC context (e.g. background
 *    or scheduled task), ensures worker thread's MDC is cleared during execution.
 * 4. Stale context protection: does not leak previous task's MDC into subsequent tasks
 *    reused by the worker thread pool.
 * 5. Automatic registration: implements ThreadPoolTaskExecutorCustomizer so every Spring Boot
 *    managed ThreadPoolTaskExecutor automatically applies this decorator.
 */
@Component
public class MdcTaskDecorator implements TaskDecorator, ThreadPoolTaskExecutorCustomizer {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Snapshot caller thread's MDC context at decoration time
        Map<String, String> callerContext = MDC.getCopyOfContextMap();

        return () -> {
            // Snapshot worker thread's pre-execution MDC context
            Map<String, String> previousContext = MDC.getCopyOfContextMap();
            try {
                if (callerContext != null && !callerContext.isEmpty()) {
                    MDC.setContextMap(callerContext);
                } else {
                    MDC.clear();
                }
                runnable.run();
            } finally {
                // Restore original worker thread MDC state (or clear if none)
                if (previousContext != null && !previousContext.isEmpty()) {
                    MDC.setContextMap(previousContext);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    @Override
    public void customize(ThreadPoolTaskExecutor taskExecutor) {
        taskExecutor.setTaskDecorator(this);
    }
}
