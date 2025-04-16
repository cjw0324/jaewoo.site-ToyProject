package com.example.demo.domain.notice.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class NotificationWebSocketHandler implements WebSocketHandler {
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = getUserIdFromQuery(session); // 쿼리에서 userId 파싱
        if (userId != null) {
            sessions.put(userId, session);
            log.info("🔌 WebSocket 연결됨: {}", userId);
        }
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {}

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket 오류", exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        String userId = getUserIdFromQuery(session);
        if (userId != null) {
            sessions.remove(userId);
            log.info("🔌 WebSocket 연결 종료: {}", userId);
        }
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    public void sendMessageToUser(String userId, String message) {
        WebSocketSession session = sessions.get(userId);
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(message));
                log.info("알림 전송 → {}: {}", userId, message);
            } catch (Exception e) {
                log.error("알림 전송 실패", e);
            }
        }
    }

    private String getUserIdFromQuery(WebSocketSession session) {
        String query = session.getUri().getQuery(); // ex: userId=123
        if (query != null && query.startsWith("userId=")) {
            return query.split("=")[1];
        }
        return null;
    }
}
