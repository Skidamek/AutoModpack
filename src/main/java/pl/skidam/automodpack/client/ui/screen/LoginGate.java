package pl.skidam.automodpack.client.ui.screen;

/**
 * Login-phase prompt. After it is left, the join it sat on is gone, so {@code ScreenImpl} never restores it: Esc and
 * cancel of whatever follows land on the multiplayer list.
 */
public interface LoginGate {}
