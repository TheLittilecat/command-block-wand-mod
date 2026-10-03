package command.wand;

import com.google.gson.JsonObject;

public final class SkillSlot {

    public static final double DEFAULT_DISTANCE = 0.5;

    public static final double SCROLL_STEP_MIN = 0.9;

    public static final double SCROLL_STEP_MAX = 75.0;

    public static final double SCROLL_SPEED_FULL = 5.0;

    public static final double SCROLL_PROPORTION = 0.24;

    public static final double MIN_DISTANCE = 0.5;
    public static final double MAX_DISTANCE = 100.0;

    public String name = "";
    public SkillType type = SkillType.SINGLE;
    public String start = "";
    public String end = "";
    public int delay = 20;
    public int interval = 10;

    public double distance = DEFAULT_DISTANCE;

    public boolean truncateCoordinates = false;

    public boolean armed() {
        return start != null && !start.isBlank();
    }

    public boolean hasForwardSelector() {
        return hasSelector(start, 'f') || hasSelector(end, 'f');
    }

    /**
     * 是否包含选择器 {@code @kind}。
     *
     * <p>要求后面紧跟选择器字符或结束（空格、标点等），避免把 {@code @foo} 误判成 {@code @f}。
     */
    private static boolean hasSelector(String command, char kind) {
        if (command == null) return false;
        for (int i = 0; i + 1 < command.length(); i++) {
            if (command.charAt(i) != '@' || command.charAt(i + 1) != kind) continue;
            int next = i + 2;
            if (next >= command.length()) return true;
            char c = command.charAt(next);
            boolean nameChar = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9' || c == '_';
            if (!nameChar) return true;
        }
        return false;
    }

    public void read(JsonObject o) {
        if (o.has("name")) name = o.get("name").getAsString();
        if (o.has("type")) {
            try { type = SkillType.valueOf(o.get("type").getAsString()); }
            catch (IllegalArgumentException ignored) { type = SkillType.SINGLE; }
        }
        if (o.has("start")) start = o.get("start").getAsString();
        if (o.has("end")) end = o.get("end").getAsString();
        if (o.has("delay")) delay = Math.max(1, o.get("delay").getAsInt());
        if (o.has("interval")) interval = Math.max(1, o.get("interval").getAsInt());
        if (o.has("distance")) distance = clampDistance(o.get("distance").getAsDouble());
        if (o.has("truncateCoordinates")) truncateCoordinates = o.get("truncateCoordinates").getAsBoolean();
    }

    public JsonObject write() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("type", type.name());
        o.addProperty("start", start);
        o.addProperty("end", end);
        o.addProperty("delay", delay);
        o.addProperty("interval", interval);
        o.addProperty("distance", distance);
        o.addProperty("truncateCoordinates", truncateCoordinates);
        return o;
    }

    public static double clampDistance(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return DEFAULT_DISTANCE;
        return Math.min(MAX_DISTANCE, Math.max(MIN_DISTANCE, d));
    }

    public static double scrollDelta(double current, double rawScroll) {
        double t = Math.min(1.0, Math.abs(rawScroll) / SCROLL_SPEED_FULL);

        double speedFactor = 0.5 + 0.5 * t;

        double proportional = Math.max(SCROLL_STEP_MIN,
                Math.min(SCROLL_STEP_MAX, current * SCROLL_PROPORTION));
        double step = proportional * speedFactor;
        return rawScroll < 0 ? -step : step;
    }
}
