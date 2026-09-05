package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.db.AuditRepository;
import dev.warasugi.warp.web.PagingParams;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

public class AuditHandler implements RouteRegistrar {
    private final AuditRepository audit;

    public AuditHandler(AuditRepository audit) {
        this.audit = audit;
    }

    @Override
    public void register(Javalin app) {
        app.get("/api/audit", this::getAudit);
    }

    @OpenApi(
            path = "/api/audit",
            methods = HttpMethod.GET,
            summary = "監査ログを取得する",
            queryParams = {
                    @OpenApiParam(name = "page", type = Integer.class)
            },
            responses = @OpenApiResponse(
                    status = "200",
                    content = @OpenApiContent(from = AuditRepository.AuditEntry[].class)))
    public void getAudit(Context ctx) throws Exception {
        int page = PagingParams.page(ctx);
        ctx.json(audit.query(100, page * 100));
    }
}
