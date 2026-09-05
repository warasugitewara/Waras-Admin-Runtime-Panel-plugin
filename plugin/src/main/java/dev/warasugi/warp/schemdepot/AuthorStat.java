package dev.warasugi.warp.schemdepot;

/** 作者別の集計。share は totalBytes に対する占有率 (0.0〜1.0)。 */
public record AuthorStat(String uuid, String name, int count, long bytes, double share) {}
