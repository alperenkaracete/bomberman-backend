package com.bomberman.config;

import com.bomberman.handler.GameSocketHandler;
import com.bomberman.room.RoomManager;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** Maximum incoming text message size (bytes). Larger messages close the connection. */
    static final int MAX_MESSAGE_BYTES = 64 * 1024;

    private final String[] allowedOrigins;

    public WebSocketConfig(
            @Value("${ALLOWED_ORIGINS:https://karacete.com,http://localhost:*,http://127.0.0.1:*}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public RoomManager roomManager() {
        return new RoomManager();
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
        registry.addHandler(new GameSocketHandler(roomManager()), "/oyun-odasi")
                .setAllowedOriginPatterns(allowedOrigins);
    }
}
