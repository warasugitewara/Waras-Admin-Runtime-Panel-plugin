package dev.warasugi.warp.schemdepot;

import io.javalin.openapi.OpenApiRequired;

/**
 * アセット1件。createdAt/updatedAt は epoch millis
 * (SchemDepot 側が Instant.toEpochMilli() で保存している)。
 * bytes は .schem の実測サイズ。ファイルが無い場合は 0 かつ fileMissing = true。
 */
public record SchemDepotAsset(
        @OpenApiRequired String id,
        @OpenApiRequired String name,
        @OpenApiRequired String authorUuid,
        @OpenApiRequired String authorName,
        long createdAt,
        long updatedAt,
        int sizeX,
        int sizeY,
        int sizeZ,
        long volume,
        long bytes,
        boolean fileMissing) {}
