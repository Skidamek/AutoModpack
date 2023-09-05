package pl.skidam.automodpack_core.utils;

public enum UpdateType {
    FULL,
    UPDATE,
    AUTOMODPACK;

    @Override
    public String toString() {
        return name().toLowerCase();
    }
}
