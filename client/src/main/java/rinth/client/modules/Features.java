package rinth.client.modules;

import java.util.List;
import rinth.features.armorpotionview.ArmorPotionView;
import rinth.features.blockoutline.BlockOutline;
import rinth.features.clearchat.ClearChat;
import rinth.features.cooldownindicator.CooldownIndicator;
import rinth.features.coordinates.Coordinates;
import rinth.features.cps.Cps;
import rinth.features.crosshair.CrosshairEditor;
import rinth.features.customscoreboard.CustomScoreboard;
import rinth.features.fpsdisplay.FpsDisplay;
import rinth.features.freelook.Freelook;
import rinth.features.fullbright.Fullbright;
import rinth.features.glintcolor.EnchantGlint;
import rinth.features.hitboxes.HitboxesModule;
import rinth.features.hitcolor.HitColor;
import rinth.features.hiteffects.HitEffects;
import rinth.features.jsmods.JsMods;
import rinth.features.keystrokes.Keystrokes;
import rinth.features.lowfire.LowFire;
import rinth.features.lowshield.LowShield;
import rinth.features.minimap.Minimap;
import rinth.features.nohurtcam.NoHurtCam;
import rinth.features.oldanimations.OldAnimations;
import rinth.features.oldtextures.OldTextures;
import rinth.features.reachdisplay.ReachDisplay;
import rinth.features.saturation.Saturation;
import rinth.features.shieldtotem.ShieldTotem;
import rinth.features.shortswords.ShortSwords;
import rinth.features.shulkertooltips.ShulkerTooltips;
import rinth.features.theme.ThemeEditor;
import rinth.features.togglesprint.ToggleSprint;
import rinth.features.waypoints.WaypointsModule;
import rinth.features.weatherchanger.WeatherChanger;
import rinth.features.zoom.Zoom;
import rinth.framework.Module;

/**
 * Every feature module in the build, in the order the module menu lists them within a category. A
 * new feature is a {@code features/<name>} project, a line in {@code :client}'s dependencies, and a
 * line here: no reflection or service loading, which the browser build's closed world can't see.
 */
final class Features {

    private Features() {}

    /** A new instance of each. */
    static List<Module> all() {
        return List.of(
                new ToggleSprint(),
                new Keystrokes(),
                new Cps(),
                new FpsDisplay(),
                new ReachDisplay(),
                new Coordinates(),
                new ArmorPotionView(),
                new ShieldTotem(),
                new Saturation(),
                new CustomScoreboard(),
                new Minimap(),
                new CooldownIndicator(),
                new HitEffects(),
                new HitboxesModule(),
                new OldAnimations(),
                new Freelook(),
                new Zoom(),
                new CrosshairEditor(),
                new LowShield(),
                new ShortSwords(),
                new WeatherChanger(),
                new Fullbright(),
                new BlockOutline(),
                new HitColor(),
                new EnchantGlint(),
                new OldTextures(),
                new ThemeEditor(),
                new ClearChat(),
                new LowFire(),
                new NoHurtCam(),
                new WaypointsModule(),
                new ShulkerTooltips(),
                new JsMods());
    }
}
