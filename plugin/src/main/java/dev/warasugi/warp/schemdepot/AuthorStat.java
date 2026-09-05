package dev.warasugi.warp.schemdepot;

import io.javalin.openapi.OpenApiRequired;

/** 作者別の集計。share は totalBytes に対する占有率 (0.0〜1.0)。 */
public record AuthorStat(@OpenApiRequired String uuid, @OpenApiRequired String name,
                         int count, long bytes, double share) {}
