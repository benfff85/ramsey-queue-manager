package com.setminusx.ramsey.qm.config;

import com.setminusx.ramsey.qm.controller.StageAdoptScheduler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subscribes to the workers' new-best announcements so the queue manager can arm a settle timer
 * the moment an improvement exists, instead of discovering it on its next poll.
 *
 * <p>Pub/sub is fire-and-forget with no delivery guarantee, which is fine: the scheduled
 * progression loop remains the fallback, so a dropped message costs latency, never correctness.
 */
@Slf4j
@Configuration
public class RedisListenerConfig {

    /** Must match the Rust worker's redis_client::BEST_RESULT_CHANNEL. */
    public static final String BEST_RESULT_CHANNEL = "best_result_events";

    /** Payload is {"stageId":N,"cliqueCount":M}; only the stage id drives the timer. */
    private static final Pattern STAGE_ID = Pattern.compile("\"stageId\"\\s*:\\s*(\\d+)");

    @Bean
    public RedisMessageListenerContainer bestResultListenerContainer(
            RedisConnectionFactory connectionFactory, StageAdoptScheduler adoptScheduler) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        MessageListener listener = (message, pattern) -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            Matcher m = STAGE_ID.matcher(body);
            if (m.find()) {
                adoptScheduler.onNewBest(Integer.parseInt(m.group(1)));
            } else {
                log.warn("Unparseable best-result event: {}", body);
            }
        };
        container.addMessageListener(listener, new ChannelTopic(BEST_RESULT_CHANNEL));
        log.info("Subscribed to Redis channel {} for new-best announcements", BEST_RESULT_CHANNEL);
        return container;
    }
}
