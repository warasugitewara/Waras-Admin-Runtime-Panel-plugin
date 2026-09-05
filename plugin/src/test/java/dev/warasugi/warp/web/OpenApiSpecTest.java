package dev.warasugi.warp.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * OpenAPI 仕様がバックエンドの実態と一致していることを守るテスト。
 *
 * WARP はバックエンドを API 仕様の SoT にしていて、フロントの api-types.ts は
 * ここで検証する JSON から生成される。注釈を書き忘れたエンドポイントは
 * 仕様に載らず、結果としてフロントから型無しで叩かれることになる。
 * それを防ぐため、ハンドラのソースに書かれた実際のルート登録を読み取って
 * 一つ残らず仕様に出ているかを突き合わせる。期待値をここに手書きしないのは、
 * 手書きするとエンドポイントを足したときに更新し忘れてテストが素通りするから。
 */
class OpenApiSpecTest {

    /** 注釈プロセッサが compileJava で吐く仕様。生成物なのでビルドディレクトリ配下。 */
    private static final Path SPEC =
            Path.of("build/classes/java/main/openapi-plugin/openapi-default.json");

    private static final Path HANDLERS =
            Path.of("src/main/java/dev/warasugi/warp/web/handlers");

    /** 例: app.delete("/api/bans/{player}", this::removeBan); */
    private static final Pattern ROUTE =
            Pattern.compile("app[.](get|post|put|patch|delete)[(][ ]*\"(/api/[^\"]+)\"");

    @Test
    void 全エンドポイントがOpenAPI仕様に載っている() throws IOException {
        assertTrue(Files.exists(SPEC),
                "仕様 JSON が無い。compileJava を先に走らせること: " + SPEC.toAbsolutePath());

        JsonNode paths = new ObjectMapper().readTree(Files.readString(SPEC, StandardCharsets.UTF_8))
                .get("paths");

        var registered = collectRoutes();
        assertFalse(registered.isEmpty(), "ハンドラからルートを 1 本も読み取れていない");

        var missing = new TreeSet<String>();
        for (String route : registered) {
            String[] parts = route.split(" ", 2);
            JsonNode entry = paths == null ? null : paths.get(parts[1]);
            if (entry == null || entry.get(parts[0].toLowerCase()) == null) {
                missing.add(route);
            }
        }

        assertTrue(missing.isEmpty(), "@OpenApi が付いていないエンドポイントがある: " + missing);
    }

    /** ハンドラのソースから登録済みルートを "METHOD /path" の形で集める。 */
    private static TreeSet<String> collectRoutes() throws IOException {
        var routes = new TreeSet<String>();
        try (Stream<Path> files = Files.list(HANDLERS)) {
            List<Path> sources = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path source : sources) {
                Matcher m = ROUTE.matcher(Files.readString(source, StandardCharsets.UTF_8));
                while (m.find()) {
                    routes.add(m.group(1).toUpperCase() + " " + m.group(2));
                }
            }
        }
        return routes;
    }
}
