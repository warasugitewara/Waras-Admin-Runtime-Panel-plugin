package dev.warasugi.warp.schemdepot;

/** SchemDepot 連携が利用できない理由。code() はそのまま HTTP レスポンスの reason になる。 */
public enum SchemDepotUnavailable {
    NOT_INSTALLED("not_installed"),
    NO_DATABASE("no_database"),
    NOT_MIGRATED("not_migrated"),
    SCHEMA_TOO_NEW("schema_too_new"),
    OPEN_FAILED("open_failed"),
    READ_FAILED("read_failed");

    private final String code;

    SchemDepotUnavailable(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
