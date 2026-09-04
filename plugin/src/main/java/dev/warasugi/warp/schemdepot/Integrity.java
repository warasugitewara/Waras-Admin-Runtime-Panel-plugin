package dev.warasugi.warp.schemdepot;

import java.util.List;

/**
 * レジストリとファイル実体の不整合。検出して報告するだけで、削除は一切行わない
 * (設計書 §2-5)。
 */
public record Integrity(
        List<MissingFile> missingFiles,
        List<OrphanFile> orphanFiles,
        long orphanBytes,
        boolean schematicsUnreadable) {

    /** DB に行はあるが .schem が存在しないもの。 */
    public record MissingFile(String id, String name, String file) {}

    /** schematics/ にあるがどの行からも参照されていない .schem。 */
    public record OrphanFile(String file, long bytes) {}

    public static Integrity empty() {
        return new Integrity(List.of(), List.of(), 0L, false);
    }
}
