package dev.warasugi.warp.schemdepot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 設計書 §2「既存アセットを絶対に失わない」の回帰テスト。
 *
 * ここが落ちたら、SchemDepot のデータを壊しうる変更が入ったということ。
 * テストを緩めるのではなく、変更のほうを直すこと。
 */
class SchemDepotReadOnlyTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final String UUID_A = "11111111-1111-1111-1111-111111111111";

    private static final Path SOURCE_DIR =
            Path.of("src/main/java/dev/warasugi/warp/schemdepot");

    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER", "VACUUM",
            "executeUpdate", "Files.delete", "Files.deleteIfExists", "Files.move",
            "Files.write", "Files.copy", "Files.newOutputStream", "setReadOnly(false)");

    @Test
    void readingDoesNotModifyDatabaseOrSchematics(@TempDir Path plugins) throws Exception {
        SchemDepotTestFixture.create(plugins)
                .addAsset("a-1", "House", UUID_A, "alice", 1000L, 2, 3, 4, 100)
                .addAsset("a-2", "Gone", UUID_A, "alice", 2000L, 1, 1, 1, -1)
                .addOrphanFile("stray.schem", 70);

        Path root = plugins.resolve("SchemDepot");
        Map<String, String> before = fingerprint(root);
        assertTrue(before.containsKey("assets.db"), "DB 本体が検査対象から漏れている");
        assertTrue(before.keySet().stream().anyMatch(k -> k.endsWith(".schem")),
                ".schem が検査対象から漏れている");

        var reader = new SchemDepotReader(plugins, LOG);
        for (int i = 0; i < 3; i++) {
            assertTrue(reader.read().available());
        }

        assertEquals(before, fingerprint(root),
                "読み取りだけで SchemDepot のファイルが変化した");
    }

    @Test
    void productionSourcesContainNoMutatingOperations() throws Exception {
        assertTrue(Files.isDirectory(SOURCE_DIR),
                "ソースディレクトリが見つからない: " + SOURCE_DIR.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String code = stripComment(line);
                    for (String token : FORBIDDEN_TOKENS) {
                        if (code.contains(token)) {
                            violations.add(file.getFileName() + ":" + (i + 1) + " → " + token);
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "SchemDepot 連携のプロダクションコードに書き込み系の操作がある: " + violations);
    }

    /**
     * 行全体がコメントの行だけを除外する。禁止語を javadoc や行コメントに書けるようにするため。
     *
     * 行末コメントは切り落とさない。文字列リテラル中の "//"（URL など）をコメント開始と
     * 誤認すると、その後ろに続く実コードごと検査対象から外れてしまうため。
     * 誤検知して落ちるほうが、見逃して通るより安全。
     */
    private static String stripComment(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
            return "";
        }
        return line;
    }

    /**
     * ディレクトリ配下の相対パス → SHA-256。内容とファイル構成の両方を捕まえる。
     *
     * SQLite のサイドカー (-wal / -shm / -journal) だけは対象外。WAL の DB は
     * 読み手が接続を開いた時点で -wal(0 バイト) と -shm が作られる。これは
     * SQLite の標準動作で、SchemDepot 自身が読むときにも同じことが起きる。
     * 守るべきは DB 本体と .schem の中身なので、そちらは 1 バイトも見逃さない。
     */
    private static Map<String, String> fingerprint(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> targets = files.filter(Files::isRegularFile)
                    .filter(SchemDepotReadOnlyTest::isNotSqliteSidecar)
                    .toList();
            for (Path file : targets) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                result.put(root.relativize(file).toString().replace('\\', '/'),
                        HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file))));
            }
        }
        return result;
    }

    private static boolean isNotSqliteSidecar(Path file) {
        String name = file.getFileName().toString();
        return !name.endsWith("-wal") && !name.endsWith("-shm") && !name.endsWith("-journal");
    }
}
