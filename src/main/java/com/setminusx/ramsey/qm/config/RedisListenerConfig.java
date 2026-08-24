package com.setminusx.ramsey.qm.config;

import com.setminusx.ramsey.qm.controller.StageEventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * Subscribes to the workers' stage announcements so the queue manager acts on them the moment they
 * happen, instead of discovering them on its next poll. What each event means, and why they are
 * handled differently, lives in {@link StageEventListener}.
 */
@Slf4j
@Configuration
public class RedisListenerConfig {

    /** Must match the Rust worker's redis_client::BEST_RESULT_CHANNEL. */
    public static final String BEST_RESULT_CHANNEL = "best_result_events";

    /** Must match the Rust worker's redis_client::STAGE_EXHAUSTED_CHANNEL. */
    public static final String STAGE_EXHAUSTED_CHANNEL = "stage_exhausted_events";

    @Bean
    public RedisMessageListenerContainer stageEventListenerContainer(
            RedisConnectionFactory connectionFactory, StageEventListener events) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        container.addMessageListener(
                (message, pattern) -> events.onBestResult(body(message.getBody())),
                new ChannelTopic(BEST_RESULT_CHANNEL));
        container.addMessageListener(
                (message, pattern) -> events.onStageComplete(body(message.getBody())),
                new ChannelTopic(STAGE_EXHAUSTED_CHANNEL));

        log.info("Subscribed to Redis channels {} and {}", BEST_RESULT_CHANNEL, STAGE_EXHAUSTED_CHANNEL);
        return container;
    }

    private static String body(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8);
    }
}
