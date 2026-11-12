package com.greysworkshop.naughtyornice;

import net.minecraft.client.gui.DrawContext;

/** Shapes for our own screens: rounded boxes, soft shadows, gradients, all with plain fills. */
final class UiPaint {
	private UiPaint() {}

	/** A box with corners rounded to radius {@code r} (in GUI pixels). */
	static void rounded(DrawContext context, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) return;
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		for (int row = 0; row < r; row++) {
			int inset = inset(r, row);
			context.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
			context.fill(x + inset, y + h - row - 1, x + w - inset, y + h - row, color);
		}
		context.fill(x, y + r, x + w, y + h - r, color);
	}

	/** A rounded box with a 1-pixel border. */
	static void framed(DrawContext context, int x, int y, int w, int h, int r, int fill, int border) {
		rounded(context, x, y, w, h, r, border);
		rounded(context, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
	}

	/** A rounded box shading from {@code top} to {@code bottom}. */
	static void roundedGradient(DrawContext context, int x, int y, int w, int h, int r, int top, int bottom) {
		if (w <= 0 || h <= 0) return;
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		for (int row = 0; row < r; row++) {
			int inset = inset(r, row);
			context.fill(x + inset, y + row, x + w - inset, y + row + 1, mix(top, bottom, (float) row / h));
			context.fill(x + inset, y + h - row - 1, x + w - inset, y + h - row, mix(top, bottom, (float) (h - row - 1) / h));
		}
		context.fillGradient(x, y + r, x + w, y + h - r, mix(top, bottom, (float) r / h), mix(top, bottom, (float) (h - r) / h));
	}

	/** A soft shadow under a box: a few widening, fading layers. */
	static void shadow(DrawContext context, int x, int y, int w, int h, int r, int strength) {
		for (int i = 4; i >= 1; i--) {
			int alpha = strength * (5 - i) / 10;
			rounded(context, x - i + 1, y - i + 3, w + 2 * i - 2, h + 2 * i - 2, r + i, alpha << 24);
		}
	}

	/** Left-to-right gradient, in 2-pixel steps. */
	static void hGradient(DrawContext context, int x, int y, int w, int h, int from, int to) {
		for (int i = 0; i < w; i += 2) {
			context.fill(x + i, y, x + Math.min(w, i + 2), y + h, mix(from, to, (float) i / Math.max(1, w - 1)));
		}
	}

	/** A flat ellipse (a shadow on the floor, a spotlight), built from rows. */
	static void ellipse(DrawContext context, int cx, int cy, int rx, int ry, int color) {
		for (int dy = -ry; dy <= ry; dy++) {
			double t = (double) dy / (ry + 0.5);
			int half = (int) Math.round(rx * Math.sqrt(Math.max(0, 1 - t * t)));
			if (half > 0) context.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
		}
	}

	/** Blends two ARGB colours. */
	static int mix(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int alpha = Math.round((a >>> 24) * (1 - t) + (b >>> 24) * t);
		int red = Math.round((a >> 16 & 0xFF) * (1 - t) + (b >> 16 & 0xFF) * t);
		int green = Math.round((a >> 8 & 0xFF) * (1 - t) + (b >> 8 & 0xFF) * t);
		int blue = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return alpha << 24 | red << 16 | green << 8 | blue;
	}

	/** The colour with its alpha scaled by {@code a} (0..1). */
	static int fade(int color, float a) {
		int alpha = Math.round((color >>> 24) * Math.max(0, Math.min(1, a)));
		return alpha << 24 | (color & 0xFFFFFF);
	}

	/** Eases {@code current} towards {@code target} over about 80 ms per step: smooth at any frame rate. */
	static float approach(float current, float target, float frameMs) {
		float step = 1 - (float) Math.exp(-Math.max(0, frameMs) / 80f);
		float next = current + (target - current) * Math.min(1, step);
		return Math.abs(target - next) < 0.002f ? target : next;
	}

	private static int inset(int r, int row) {
		double dy = r - row - 0.5;
		return (int) Math.round(r - Math.sqrt(Math.max(0, (double) r * r - dy * dy)));
	}
}
