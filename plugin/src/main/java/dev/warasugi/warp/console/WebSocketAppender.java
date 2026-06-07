package dev.warasugi.warp.console;

import dev.warasugi.warp.db.LogRepository;
import dev.warasugi.warp.ws.AdminWsHandler;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import java.util.Map;

public class WebSocketAppender extends AbstractAppender {
    private final AdminWsHandler ws;
    private final LogRepository logRepo;

    public WebSocketAppender(AdminWsHandler ws, LogRepository logRepo) {
        super("WarpWebSocket", null, null, true, Property.EMPTY_ARRAY);
        this.ws = ws;
        this.logRepo = logRepo;
    }

    @Override
    public void append(LogEvent event) {
        long ts = event.getInstant().getEpochMillisecond();
        String level = event.getLevel().name();
        String logger = event.getLoggerName();
        String message = event.getMessage().getFormattedMessage();

        ws.broadcast("log", Map.of("level", level, "msg", message, "time", ts));
        try {
            logRepo.insert(ts, level, logger, message);
        } catch (Exception e) {
            // ignore db errors in log path
        }
    }

    public static void register(AdminWsHandler ws, LogRepository logRepo) {
        var appender = new WebSocketAppender(ws, logRepo);
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().addAppender(appender);
        ctx.getConfiguration().getRootLogger().addAppender(appender, Level.ALL, null);
        ctx.updateLoggers();
    }
}
