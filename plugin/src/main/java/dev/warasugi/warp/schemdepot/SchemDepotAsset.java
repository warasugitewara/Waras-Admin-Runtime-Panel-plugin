package dev.warasugi.warp.schemdepot;

/**
 * アセット1件。createdAt/updatedAt は epoch millis
 * (SchemDepot 側が Instant.toEpochMilli() で保存している)。
 * bytes は .schem の実測サイズ。ファイルが無い場合は 0 かつ fileMissing = true。
 */
public record SchemDepotAsset(
        String id,
        String name,
        String authorUuid,
        String authorName,
        long createdAt,
        long updatedAt,
        int sizeX,
        int sizeY,
        int sizeZ,
        long volume,
        long bytes,
        boolean fileMissing) {}
