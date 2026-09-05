package dev.warasugi.warp.schemdepot;

import io.javalin.openapi.OpenApiRequired;

import java.util.List;

/**
 * レジストリとファイル実体の不整合。検出して報告するだけで、削除は一切行わない
 * (設計書 §2-5)。
 */
public record Integrity(
        @OpenApiRequired List<MissingFile> missingFiles,
        @OpenApiRequired List<OrphanFile> orphanFiles,
        long orphanBytes,
        boolean schematicsUnreadable) {

    /** DB に行はあるが .schem が存在しないもの。 */
    public record MissingFile(@OpenApiRequired String id, @OpenApiRequired String name,
                              @OpenApiRequired String file) {}

    /** schematics/ にあるがどの行からも参照されていない .schem。 */
    public record OrphanFile(@OpenApiRequired String file, long bytes) {}

    public static Integrity empty() {
        return new Integrity(List.of(), List.of(), 0L, false);
    }
}
