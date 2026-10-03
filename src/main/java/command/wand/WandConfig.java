package command.wand;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WandConfig {
    public static final int SLOTS = 12;

    public final SkillSlot[] slots = new SkillSlot[SLOTS];
    public int activeSlot = 0;

    public WandConfig() {
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = new SkillSlot();
        }
    }

    public SkillSlot active() {
        return slots[activeSlot];
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("command_wand.json");
    }

    public static WandConfig load() {
        WandConfig cfg = new WandConfig();
        try {
            Path p = file();
            if (Files.isRegularFile(p)) {
                String raw = Files.readString(p, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
                if (root.has("activeSlot")) {
                    cfg.activeSlot = clampSlot(root.get("activeSlot").getAsInt());
                }
                JsonArray arr = root.has("slots") ? root.getAsJsonArray("slots") : null;
                if (arr != null) {
                    for (int i = 0; i < Math.min(arr.size(), SLOTS); i++) {
                        cfg.slots[i].read(arr.get(i).getAsJsonObject());
                    }
                }
            }
        } catch (Exception ignored) {}
        return cfg;
    }

    public void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("activeSlot", activeSlot);
            JsonArray arr = new JsonArray();
            for (SkillSlot slot : slots) {
                arr.add(slot.write());
            }
            root.add("slots", arr);
            Path p = file();
            Files.createDirectories(p.getParent());
            Files.writeString(p, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    public static int clampSlot(int i) {
        return Math.max(0, Math.min(SLOTS - 1, i));
    }
}
