package net.shoreline.client.impl.gui.hud;

import com.google.gson.*;
import net.shoreline.client.Shoreline;
import net.shoreline.client.impl.gui.hud.widget.HudWidget;
import net.shoreline.client.impl.gui.hud.widget.impl.*;
import net.shoreline.client.util.Globals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HudWidgetManager implements Globals {

    private static final Logger LOGGER = LoggerFactory.getLogger("HudWidgetManager");
    private static HudWidgetManager INSTANCE;

    private final List<HudWidget> widgets = new ArrayList<>();

    private HudWidgetManager() {
        register(new WatermarkWidget());
        register(new MetricsWidget());
        register(new CoordsWidget());
        register(new DirectionWidget());
        register(new ArmorWidget());
        register(new InventoryWidget());
        register(new PotionEffectsWidget());
        register(new MapWidget());
    }

    public static HudWidgetManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new HudWidgetManager();
        }
        return INSTANCE;
    }

    private void register(HudWidget widget) {
        widgets.add(widget);
    }

    public List<HudWidget> getWidgets() {
        return Collections.unmodifiableList(widgets);
    }

    public void save() {
        Path file = Shoreline.CONFIG.getClientDirectory().resolve("hud_layout.json");
        try {
            JsonArray arr = new JsonArray();
            for (HudWidget w : widgets) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name",    w.getName());
                obj.addProperty("x",       w.getX());
                obj.addProperty("y",       w.getY());
                obj.addProperty("enabled", w.isEnabled());
                arr.add(obj);
            }
            Files.writeString(file,
                    new GsonBuilder().setPrettyPrinting().create().toJson(arr),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("HUD 레이아웃 저장 실패", e);
        }
    }

    public void load() {
        Path file = Shoreline.CONFIG.getClientDirectory().resolve("hud_layout.json");
        if (!Files.exists(file)) return;
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JsonArray arr = JsonParser.parseString(content).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String  name    = obj.get("name").getAsString();
                float   x       = obj.get("x").getAsFloat();
                float   y       = obj.get("y").getAsFloat();
                boolean enabled = obj.get("enabled").getAsBoolean();
                widgets.stream()
                        .filter(w -> w.getName().equals(name))
                        .findFirst()
                        .ifPresent(w -> {
                            w.setX(x);
                            w.setY(y);
                            w.setEnabled(enabled);
                        });
            }
        } catch (Exception e) {
            LOGGER.error("HUD 레이아웃 로드 실패", e);
        }
    }
}
