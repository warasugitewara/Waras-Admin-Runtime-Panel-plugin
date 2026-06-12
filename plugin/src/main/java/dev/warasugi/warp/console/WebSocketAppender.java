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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class WebSocketAppender extends AbstractAppender {
    private final AdminWsHandler ws;
    private final LogRepository logRepo;
    private final ExecutorService executor;

    public WebSocketAppender(AdminWsHandler ws, LogRepository logRepo) {
        super("WarpWebSocket", null, null, true, Property.EMPTY_ARRAY);
        this.ws = ws;
        this.logRepo = logRepo;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "warp-log-appender");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void append(LogEvent event) {
        long ts = event.getInstant().getEpochMillisecond();
        String level = event.getLevel().name();
        String logger = event.getLoggerName();
        String message = event.getMessage().getFormattedMessage();

        // WS broadcast と DB 書き込みはログ発生スレッド（メインスレッド含む）を
        // 塞がないよう専用ワーカースレッドへ委譲する
        try {
            executor.submit(() -> {
                ws.broadcast("log", Map.of("level", level, "msg", message, "time", ts));
                try {
                    logRepo.insert(ts, level, logger, message);
                } catch (Exception e) {
                    // ignore db errors in log path
                }
            });
        } catch (RejectedExecutionException ignored) {
            // shutdown 中のログは配信をスキップする
        }
    }

    private void shutdownExecutor() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static WebSocketAppender register(AdminWsHandler ws, LogRepository logRepo) {
        var appender = new WebSocketAppender(ws, logRepo);
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().addAppender(appender);
        ctx.getConfiguration().getRootLogger().addAppender(appender, Level.ALL, null);
        ctx.updateLoggers();
        return appender;
    }

    public static void unregister(WebSocketAppender appender) {
        var ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().getRootLogger().removeAppender(appender.getName());
        ctx.updateLoggers();
        appender.stop();
        appender.shutdownExecutor();
    }
}
