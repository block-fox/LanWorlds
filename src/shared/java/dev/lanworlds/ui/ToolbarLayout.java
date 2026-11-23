package dev.lanworlds.ui;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** Screen-space positions for the multiplayer entry buttons. */
public final class ToolbarLayout {
    public static final int SIZE = 20;
    public static final int GAP = 4;

    public enum Style { ICON, TEXT, BOTH }
    public enum Anchor { AUTO, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public enum RadminAnchor {
        @SerializedName(value = "TITLE_BEFORE", alternate = {"AUTO", "TOP_LEFT"}) TITLE_BEFORE,
        @SerializedName(value = "TITLE_AFTER", alternate = "TOP_RIGHT") TITLE_AFTER,
        BOTTOM_LEFT, BOTTOM_RIGHT
    }

    public record Point(double x, double y) {
        public Point {
            x = Double.isFinite(x) ? Math.clamp(x, 0, 1) : 0;
            y = Double.isFinite(y) ? Math.clamp(y, 0, 1) : 0;
        }
    }

    public record Rect(int x, int y, int width, int height) {
        public boolean overlaps(Rect other) {
            return x < other.x + other.width && x + width > other.x
                    && y < other.y + other.height && y + height > other.y;
        }
    }

    private ToolbarLayout() {}

    public static Rect onScreen(int x, int y, int width, int screenWidth, int screenHeight) {
        return new Rect(Math.clamp(x, 0, Math.max(0, screenWidth - width)),
                Math.clamp(y, 0, Math.max(0, screenHeight - SIZE)), width, SIZE);
    }

    public static Point save(Rect rect, int screenWidth, int screenHeight) {
        return new Point((double) rect.x / Math.max(1, screenWidth - rect.width),
                (double) rect.y / Math.max(1, screenHeight - SIZE));
    }

    public static Rect restore(Point point, int width, int screenWidth, int screenHeight) {
        return onScreen((int) Math.round(point.x * (screenWidth - width)),
                (int) Math.round(point.y * (screenHeight - SIZE)), width, screenWidth, screenHeight);
    }

    public static Rect placeRadmin(RadminAnchor anchor, Rect title, int width, int screenWidth,
                                   int screenHeight, List<Rect> occupied) {
        if (anchor == RadminAnchor.BOTTOM_LEFT || anchor == RadminAnchor.BOTTOM_RIGHT) {
            return place(anchor == RadminAnchor.BOTTOM_LEFT ? Anchor.BOTTOM_LEFT : Anchor.BOTTOM_RIGHT,
                    width, screenWidth, screenHeight, occupied);
        }
        boolean left = anchor == RadminAnchor.TITLE_BEFORE;
        int edge = left ? title.x - GAP - width : title.x + title.width + GAP;
        int oppositeEdge = left ? title.x + title.width + GAP : title.x - GAP - width;
        Rect preferred = findBesideTitle(edge, left ? -1 : 1, width, screenWidth, screenHeight, occupied);
        if (preferred != null) return preferred;
        Rect opposite = findBesideTitle(oppositeEdge, left ? 1 : -1, width, screenWidth, screenHeight, occupied);
        if (opposite != null) return opposite;
        return place(left ? Anchor.TOP_LEFT : Anchor.TOP_RIGHT, width, screenWidth, screenHeight, occupied);
    }

    private static Rect findBesideTitle(int x, int direction, int width, int screenWidth, int screenHeight,
                                        List<Rect> occupied) {
        for (; x >= GAP && x + width <= screenWidth - GAP; x += direction) {
            Rect candidate = onScreen(x, 6, width, screenWidth, screenHeight);
            if (fits(candidate, occupied)) return candidate;
        }
        return null;
    }

    private static boolean fits(Rect candidate, List<Rect> occupied) {
        Rect padded = new Rect(candidate.x - GAP, candidate.y - GAP,
                candidate.width + 2 * GAP, candidate.height + 2 * GAP);
        return occupied.stream().noneMatch(padded::overlaps);
    }

    public static Rect place(Anchor anchor, int width, int screenWidth, int screenHeight, List<Rect> occupied) {
        int maxX = Math.max(0, screenWidth - width - GAP);
        int maxY = Math.max(0, screenHeight - SIZE - GAP);
        boolean right = anchor == Anchor.TOP_RIGHT || anchor == Anchor.BOTTOM_RIGHT;
        boolean bottom = anchor == Anchor.BOTTOM_LEFT || anchor == Anchor.BOTTOM_RIGHT;
        Rect preferred = onScreen(right ? maxX : GAP, bottom ? maxY : 6, width, screenWidth, screenHeight);
        for (int row = 0; row <= maxY; row++) {
            int y = Math.clamp(preferred.y + (bottom ? -row : row), 0, maxY);
            for (int offset = 0; offset <= maxX; offset++) {
                int x = right ? maxX - offset : GAP + offset;
                Rect candidate = onScreen(x, y, width, screenWidth, screenHeight);
                if (fits(candidate, occupied)) return candidate;
            }
        }
        return preferred;
    }
}
