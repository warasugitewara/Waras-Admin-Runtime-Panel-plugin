package dev.warasugi.warp.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.warasugi.warp.auth.JwtManager;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsErrorContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AdminWsHandler implements RouteRegistrar {
    private final JwtManager jwt;
    private final Set<WsContext> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminWsHandler(JwtManager jwt) {
        this.jwt = jwt;
    }

    @Override
    public void register(Javalin app) {
        app.ws("/ws", wsConfig -> {
            wsConfig.onConnect(this::onConnect);
            wsConfig.onClose(this::onClose);
            wsConfig.onError(this::onError);
        });
    }

    public void onConnect(WsConnectContext ctx) {
        String token = ctx.cookie("jwt");
        if (token == null || !jwt.isValid(token)) {
            ctx.closeSession(4401, "Unauthorized");
            return;
        }
        sessions.add(ctx);
    }

    public void onClose(WsCloseContext ctx) {
        sessions.remove(ctx);
    }

    public void onError(WsErrorContext ctx) {
        sessions.remove(ctx);
    }

    public void broadcast(String type, Object data) {
        if (sessions.isEmpty()) return;
        try {
            String json = mapper.writeValueAsString(Map.of("type", type, "data", data));
            for (var session : sessions) {
                try {
                    session.send(json);
                } catch (Exception ignored) {
                    sessions.remove(session);
                }
            }
        } catch (Exception e) {
            // ignore serialization errors
        }
    }
}
