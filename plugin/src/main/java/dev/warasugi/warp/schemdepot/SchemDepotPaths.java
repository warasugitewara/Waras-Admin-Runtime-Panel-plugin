package dev.warasugi.warp.schemdepot;

import java.nio.file.Path;

/** SchemDepot のデータフォルダ内で解決済みのパス。すべて読み取り専用に扱う。 */
public record SchemDepotPaths(Path root, Path database, Path schematics) {}
