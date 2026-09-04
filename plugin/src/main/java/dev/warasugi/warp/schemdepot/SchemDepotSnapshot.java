package dev.warasugi.warp.schemdepot;

import java.util.List;

/** 1回の読み取り結果すべて。3本の API はこれ1つから応答する。 */
public record SchemDepotSnapshot(
        List<SchemDepotAsset> assets,
        int totalCount,
        long totalBytes,
        int authorCount,
        List<AuthorStat> authors,
        Integrity integrity) {}
