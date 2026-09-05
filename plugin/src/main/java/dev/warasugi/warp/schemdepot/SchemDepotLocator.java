package dev.warasugi.warp.schemdepot;

import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * plugins/SchemDepot/ を検出し、config.yml から DB とスキマティック置き場のパスを解決する。
 *
 * ここで解決したパスは読み取りにしか使わない。SchemDepot 本体の
 * SchemDepotConfig.requireNonBlankPath と同じ「単一パス要素」制約を課し、
 * データフォルダの外を読みに行かないようにする。
 */
public final class SchemDepotLocator {

    public static final String FOLDER_NAME = "SchemDepot";
    private static final String DEFAULT_DATABASE = "assets.db";
    private static final String DEFAULT_SCHEMATICS = "schematics";
    private static final char[] SEPARATORS = {'/', '\\', ':'};

    private SchemDepotLocator() {}

    public record Result(SchemDepotPaths paths, SchemDepotUnavailable reason) {
        public boolean available() {
            return paths != null;
        }

        static Result ok(SchemDepotPaths paths) {
            return new Result(paths, null);
        }

        static Result unavailable(SchemDepotUnavailable reason) {
            return new Result(null, reason);
        }
    }

    public static Result locate(Path pluginsDir, Logger logger) {
        Path root = pluginsDir.resolve(FOLDER_NAME);
        if (!Files.isDirectory(root)) {
            return Result.unavailable(SchemDepotUnavailable.NOT_INSTALLED);
        }

        String databaseName = DEFAULT_DATABASE;
        String schematicsName = DEFAULT_SCHEMATICS;

        Path configFile = root.resolve("config.yml");
        if (Files.isRegularFile(configFile)) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.loadFromString(Files.readString(configFile));
                databaseName = sanitizeName(
                        yaml.getString("storage.database-file"),
                        DEFAULT_DATABASE, "storage.database-file", logger);
                schematicsName = sanitizeName(
                        yaml.getString("storage.schematics-directory"),
                        DEFAULT_SCHEMATICS, "storage.schematics-directory", logger);
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "SchemDepot の config.yml を読めませんでした。既定のパスを使います。", e);
            }
        }

        Path database = root.resolve(databaseName);
        if (!Files.isRegularFile(database)) {
            return Result.unavailable(SchemDepotUnavailable.NO_DATABASE);
        }
        return Result.ok(new SchemDepotPaths(root, database, root.resolve(schematicsName)));
    }

    /** 単一のファイル名/ディレクトリ名でなければ fallback に落とす。 */
    static String sanitizeName(String value, String fallback, String key, Logger logger) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String reason = rejectionReason(value);
        if (reason != null) {
            logger.warning("SchemDepot の config.yml: " + key + " が \"" + value
                    + "\" (" + reason + ")。\"" + fallback + "\" を使います。");
            return fallback;
        }
        return value;
    }

    private static String rejectionReason(String value) {
        if (value.equals(".") || value.equals("..")) {
            return "相対パス要素";
        }
        for (char separator : SEPARATORS) {
            if (value.indexOf(separator) >= 0) {
                return "パス区切りまたはドライブレターを含む";
            }
        }
        Path path;
        try {
            path = Paths.get(value);
        } catch (InvalidPathException e) {
            return "このプラットフォームで無効なファイル名";
        }
        if (path.isAbsolute() || path.getRoot() != null) {
            return "絶対パス";
        }
        if (path.getNameCount() != 1) {
            return "複数のパス要素を含む";
        }
        return null;
    }
}
