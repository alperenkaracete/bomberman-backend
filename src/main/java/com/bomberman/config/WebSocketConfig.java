package com.bomberman.config;

import com.bomberman.handler.GameSocketHandler;
import com.bomberman.room.RoomManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** Maximum incoming text message size (bytes). Larger messages close the connection. */
    static final int MAX_MESSAGE_BYTES = 64 * 1024;

    /** How often empty rooms past their grace period are swept. */
    static final long SWEEP_PERIOD_SECONDS = 5;

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final String[] allowedOrigins;
    private final long emptyRoomTtlSeconds;

    public WebSocketConfig(
            @Value("${ALLOWED_ORIGINS:https://karacete.com,http://localhost:*,http://127.0.0.1:*}") String[] allowedOrigins,
            @Value("${EMPTY_ROOM_TTL_SECONDS:120}") long emptyRoomTtlSeconds) {
        this.allowedOrigins = allowedOrigins;
        this.emptyRoomTtlSeconds = emptyRoomTtlSeconds;
    }

    @Bean
    public RoomManager roomManager() {
        return new RoomManager(Duration.ofSeconds(emptyRoomTtlSeconds), Clock.systemUTC());
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService socketScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "socket-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /** Schedules a task that must keep running even if one run throws. */
    private void every(long seconds, Runnable task) {
        socketScheduler().scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("Scheduled task failed", e);
            }
        }, seconds, seconds, TimeUnit.SECONDS);
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_MESSAGE_BYTES);
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        RoomManager roomManager = roomManager();
        every(SWEEP_PERIOD_SECONDS, roomManager::sweepExpired);
        registry.addHandler(new GameSocketHandler(roomManager), "/oyun-odasi")
                .setAllowedOriginPatterns(allowedOrigins);
    }
}
