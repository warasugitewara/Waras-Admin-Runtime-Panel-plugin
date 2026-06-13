package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.db.AuditRepository;
import dev.warasugi.warp.web.PagingParams;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;

public class AuditHandler implements RouteRegistrar {
    private final AuditRepository audit;

    public AuditHandler(AuditRepository audit) {
        this.audit = audit;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/audit", this::getAudit);
    }

    public void getAudit(Context ctx) throws Exception {
        int page = PagingParams.page(ctx);
        ctx.json(audit.query(100, page * 100));
    }
}
