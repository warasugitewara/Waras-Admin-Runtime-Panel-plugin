package dev.warasugi.warp.web.handlers;

import dev.warasugi.warp.schemdepot.AuthorStat;
import dev.warasugi.warp.schemdepot.Integrity;
import dev.warasugi.warp.schemdepot.SchemDepotAsset;
import dev.warasugi.warp.schemdepot.SchemDepotReader;
import dev.warasugi.warp.schemdepot.SchemDepotSnapshot;
import dev.warasugi.warp.web.PagingParams;
import dev.warasugi.warp.web.RouteRegistrar;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * SchemDepot のレジストリを閲覧するための読み取り専用エンドポイント。
 *
 * GET しか登録しない (設計書 §2-6)。SchemDepot が未導入でもルートは登録し、
 * status が available=false を返すことでフロント側が項目ごと隠す。
 */
public class SchemDepotHandler implements RouteRegistrar {

    static final int PAGE_SIZE = 50;

    private final SchemDepotReader reader;

    public SchemDepotHandler(SchemDepotReader reader) {
        this.reader = reader;
    }

    public record StatusDto(boolean available, String reason) {}

    public record AssetsDto(int total, int page, int pageSize, List<SchemDepotAsset> items) {}

    public record StatsDto(int totalCount, long totalBytes, int authorCount,
                           List<AuthorStat> authors, Integrity integrity) {}

    @Override
    public void register(Javalin app) {
        app.get("/api/schemdepot/status", this::getStatus);
        app.get("/api/schemdepot/assets", this::getAssets);
        app.get("/api/schemdepot/stats", this::getStats);
    }

    public void getStatus(Context ctx) {
        SchemDepotReader.Result result = reader.read();
        ctx.json(new StatusDto(result.available(),
                result.available() ? null : result.reason().code()));
    }

    public void getAssets(Context ctx) {
        SchemDepotReader.Result result = reader.read();
        int page = PagingParams.page(ctx);
        if (!result.available()) {
            ctx.json(new AssetsDto(0, page, PAGE_SIZE, List.of()));
            return;
        }

        List<SchemDepotAsset> filtered = filter(result.snapshot().assets(), ctx.queryParam("q"));
        filtered = sort(filtered, ctx.queryParam("sort"), ctx.queryParam("order"));

        int from = Math.min(page * PAGE_SIZE, filtered.size());
        int to = Math.min(from + PAGE_SIZE, filtered.size());
        ctx.json(new AssetsDto(filtered.size(), page, PAGE_SIZE, filtered.subList(from, to)));
    }

    public void getStats(Context ctx) {
        SchemDepotReader.Result result = reader.read();
        if (!result.available()) {
            ctx.json(new StatsDto(0, 0L, 0, List.of(), Integrity.empty()));
            return;
        }
        SchemDepotSnapshot snap = result.snapshot();
        ctx.json(new StatsDto(snap.totalCount(), snap.totalBytes(), snap.authorCount(),
                snap.authors(), snap.integrity()));
    }

    private static List<SchemDepotAsset> filter(List<SchemDepotAsset> assets, String query) {
        if (query == null || query.isBlank()) {
            return assets;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return assets.stream()
                .filter(a -> a.name().toLowerCase(Locale.ROOT).contains(needle)
                        || a.authorName().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    private static List<SchemDepotAsset> sort(List<SchemDepotAsset> assets, String sort, String order) {
        Comparator<SchemDepotAsset> comparator = switch (sort == null ? "created" : sort) {
            case "name" -> Comparator.comparing(a -> a.name().toLowerCase(Locale.ROOT));
            case "size" -> Comparator.comparingLong(SchemDepotAsset::bytes);
            case "author" -> Comparator.comparing(a -> a.authorName().toLowerCase(Locale.ROOT));
            default -> Comparator.comparingLong(SchemDepotAsset::createdAt);
        };
        // 同着の並びを安定させる
        comparator = comparator.thenComparing(SchemDepotAsset::id);
        if (!"asc".equalsIgnoreCase(order)) {
            comparator = comparator.reversed();
        }
        return assets.stream().sorted(comparator).toList();
    }
}
