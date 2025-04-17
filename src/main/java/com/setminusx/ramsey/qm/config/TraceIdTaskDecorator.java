package com.setminusx.ramsey.qm.config;

import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import java.util.UUID;

public class TraceIdTaskDecorator implements TaskDecorator {
    @Override
    public @NotNull Runnable decorate(@NotNull Runnable runnable) {
        return () -> {
            String traceId = UUID.randomUUID().toString();
            MDC.put("traceId", traceId);
            try {
                runnable.run();
            } finally {
                MDC.remove("traceId");
            }
        };
    }
}
