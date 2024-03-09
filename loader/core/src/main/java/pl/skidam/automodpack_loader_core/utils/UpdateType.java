package pl.skidam.automodpack_loader_core.utils;

public enum UpdateType {
    FULL,
    UPDATE,
    SELECT,
    AUTOMODPACK;

    @Override
    public String toString() {
        return name().toLowerCase();
    }
}
