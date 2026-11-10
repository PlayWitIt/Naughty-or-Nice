package com.greysworkshop.naughtyornice;

/**
 * The colours land is shown in, the same on the radar and the Intel map. (Chunks used to be drawn in the world too, on
 * K; that was removed in 1.21.0. Nothing is drawn in the world now.)
 */
public final class ChunkOverlay {
	/** Red new land, blue land you were first in (an earlier visit), green old land (someone was here), yellow trails. */
	public static final int FRESH = 0xFF4040, YOURS = 0x4C8CFF, OLD = 0x3CDC5A, TRAIL = 0xFFE040, UNKNOWN = 0x9AA4AE;

	private ChunkOverlay() {}
}
