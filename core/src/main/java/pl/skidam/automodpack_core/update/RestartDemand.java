package pl.skidam.automodpack_core.update;

/**
 * What applying a plan means for a relaunch. Preload only produces {@link #REQUIRED} or {@link #NONE}:
 * this boot either cannot absorb the work, or the projection loader can. In-game also produces
 * {@link #OFFERED} when files changed that a running game might not re-read.
 */
public enum RestartDemand {
	REQUIRED,
	OFFERED,
	NONE
}
