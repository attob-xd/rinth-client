package rinth.client.settings;

import rinth.client.RinthBuild;
import rinth.client.cosmetics.WardrobeScreen;
import rinth.client.modules.RinthModules;
import rinth.client.modules.ModuleScreenshots;
import rinth.client.online.AccountScreen;
import rinth.client.online.ChatMenu;
import rinth.client.online.RinthOnline;
import rinth.client.recorder.RecorderScreen;
import rinth.client.recorder.ScreenRecorder;
import rinth.client.ui.ControlList;
import rinth.client.ui.Icons;
import rinth.client.ui.ListScreen;
import rinth.client.ui.Look;
import rinth.client.ui.Panels;
import rinth.client.ui.Theme;
import rinth.client.ui.Ui;
import rinth.framework.online.Online;
import rinth.online.OnlineService;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Rinth Settings: the pause menu's one way into everything Rinth adds (the module menu, the HUD
 * editor, the Wardrobe, the screenshot gallery, the screen recorder, Rinth Chat and the account),
 * and the client's own switches ({@link ClientSettings}). The title carries Rinth's badge.
 */
public final class RinthSettingsScreen extends ListScreen {

    /** The title, also the pause menu's button's label. */
    public static final Component TITLE = Component.literal("Rinth Settings");

    private long revision;
    private @Nullable StringWidget titleWidget;

    public RinthSettingsScreen(@Nullable Screen parent) {
        super(TITLE, parent);
    }

    @Override
    protected Component caption() {
        return Component.literal("Rinth Client " + RinthBuild.VERSION);
    }

    @Override
    protected List<ControlList.Row> rows(ControlList list) {
        return Panels.classic() ? classicRows(list) : frostRows(list);
    }

    /**
     * The classic look's rows: the game's own buttons, two to a row as in its options screens, each
     * saying what it opens in its tooltip.
     */
    private List<ControlList.Row> classicRows(ControlList list) {
        List<ControlList.Row> rows = new ArrayList<>();
        rows.add(list.heading(Component.literal("Menus")));
        List<AbstractWidget> menus = new ArrayList<>();
        menus.add(menu("Modules...", "Turn modules on and off and change their settings.", () -> {
            RinthModules.openMenu(this);
        }));
        menus.add(menu("Edit HUD...", "Move and resize HUD elements.", () -> {
            RinthModules.openHudEditor(this);
        }));
        menus.add(menu("Cosmetics...", "Capes, hats, wings, trails and emotes.", () -> {
            minecraft.gui.setScreen(new WardrobeScreen(this));
        }));
        menus.add(menu("Screenshots...", "View, rename and download screenshots.", () -> {
            ModuleScreenshots.openGallery(this);
        }));
        Button record = menu(
                "Record Screen...",
                !ScreenRecorder.supported()
                        ? "Only in the browser build."
                        : ScreenRecorder.active()
                                ? "Recording now. Open to stop and save."
                                : "Record the game, its sound and your mic.",
                () -> minecraft.gui.setScreen(new RecorderScreen(this)));
        record.active = ScreenRecorder.supported();
        menus.add(record);
        menus.add(menu("Rinth Chat...", "Friends and messages with Rinth players.", () -> {
            ChatMenu.open(this);
        }));
        OnlineService online = RinthOnline.service();
        boolean signedIn = online.status() == Online.Status.SIGNED_IN;
        menus.add(menu(
                "Rinth Account...",
                signedIn ? "Signed in. Manage your account." : "Sign in to chat, add friends and save cosmetics.",
                () -> minecraft.gui.setScreen(new AccountScreen(this))));
        pairs(list, menus, rows);

        rows.add(list.heading(Component.literal("Settings")));
        List<AbstractWidget> settings = new ArrayList<>();
        settings.add(option(
                "Minecraft's Look",
                "Minecraft's own menus, font, panorama and loading screen instead of Rinth's. Takes effect"
                        + " the next time the game starts (reload the page).",
                Look.vanillaNext(),
                on -> Look.chooseVanilla(on)));
        settings.add(option(
                "Copy Screenshots",
                "Copies each screenshot (F2) to the clipboard too.",
                ClientSettings.COPY_SCREENSHOTS.get(),
                ClientSettings.COPY_SCREENSHOTS::set));
        settings.add(option(
                "Rinth Badges",
                "Rinth's badge beside other signed-in Rinth players' names.",
                ClientSettings.SHOW_BADGES.get(),
                ClientSettings.SHOW_BADGES::set));
        CycleButton<Boolean> myBadge = option(
                "Show My Badge",
                "Shows Rinth's badge beside your name to other Rinth players. Needs a Rinth account.",
                online.showsBadge(),
                on -> online.setShowBadge(on, result -> revision++));
        myBadge.active = signedIn;
        settings.add(myBadge);
        settings.add(option(
                "Usage Counts",
                "Once a day, sends Rinth a random number, the version and the build, nothing else. It"
                        + " helps us know how many people play.",
                ClientSettings.USAGE_COUNTS.get(),
                ClientSettings.USAGE_COUNTS::set));
        pairs(list, settings, rows);
        return rows;
    }

    private static Button menu(String label, String tooltip, Runnable action) {
        Button button = Ui.button(Component.literal(label), Theme.CONTROL_WIDTH, action);
        button.setTooltip(Ui.tooltip(tooltip));
        return button;
    }

    private CycleButton<Boolean> option(String label, String tooltip, boolean value, Consumer<Boolean> set) {
        CycleButton<Boolean> button = Ui.labelledToggle(Component.literal(label), value, Theme.CONTROL_WIDTH, on -> {
            set.accept(on);
            revision++;
        });
        button.setTooltip(Ui.tooltip(tooltip));
        return button;
    }

    /** {@code widgets} two to a row. */
    private static void pairs(ControlList list, List<AbstractWidget> widgets, List<ControlList.Row> rows) {
        for (int i = 0; i < widgets.size(); i += 2) {
            rows.add(list.across(widgets.subList(i, Math.min(i + 2, widgets.size()))));
        }
    }

    /** Rinth's own look's rows: glass tiles for the menus, switches for the settings. */
    private List<ControlList.Row> frostRows(ControlList list) {
        List<ControlList.Row> rows = new ArrayList<>();
        rows.add(list.heading(Component.literal("Menus")));
        List<Ui.Tile> tiles = new ArrayList<>();
        tiles.add(tile(Icons.GRID, "Modules", "Turn modules on and off and change their settings.", () -> {
            RinthModules.openMenu(this);
        }));
        tiles.add(tile(Icons.LAYOUT, "Edit HUD", "Move and resize HUD elements.", () -> {
            RinthModules.openHudEditor(this);
        }));
        tiles.add(tile(Icons.HANGER, "Cosmetics", "Capes, hats, wings, trails and emotes.", () -> {
            minecraft.gui.setScreen(new WardrobeScreen(this));
        }));
        tiles.add(tile(Icons.CAMERA, "Screenshots", "View, rename and download screenshots.", () -> {
            ModuleScreenshots.openGallery(this);
        }));
        // Shown even where it can't record (the desktop), so it's clear where it lives.
        Ui.Tile record = Ui.tile(
                Icons.TARGET,
                Component.literal("Record Screen"),
                () -> !ScreenRecorder.supported()
                        ? "Only in the browser build."
                        : ScreenRecorder.active()
                                ? "Recording now. Open to stop and save."
                                : "Record the game, its sound and your mic.",
                () -> minecraft.gui.setScreen(new RecorderScreen(this)));
        record.active = ScreenRecorder.supported();
        tiles.add(record);
        tiles.add(tile(Icons.CHAT, "Rinth Chat", "Friends and messages with Rinth players.", () -> {
            ChatMenu.open(this);
        }));
        tiles.add(Ui.tile(
                Icons.PERSON,
                Component.literal("Rinth Account"),
                () -> RinthOnline.service().status() == Online.Status.SIGNED_IN
                        ? "Signed in. Manage your account."
                        : "Sign in to chat, add friends and save cosmetics.",
                () -> minecraft.gui.setScreen(new AccountScreen(this))));
        // Two to a row.
        for (int i = 0; i < tiles.size(); i += 2) {
            rows.add(list.across(tiles.subList(i, Math.min(i + 2, tiles.size()))));
        }

        rows.add(list.heading(Component.literal("Settings")));
        rows.add(minecraftLook(list));
        rows.add(toggle(
                list,
                "Copy screenshots",
                "Copies each screenshot (F2) to the clipboard too.",
                ClientSettings.COPY_SCREENSHOTS));
        rows.add(toggle(
                list,
                "Show Rinth badges",
                "Rinth's badge beside other signed-in Rinth players' names.",
                ClientSettings.SHOW_BADGES));
        rows.add(showMyBadge(list));
        rows.add(toggle(
                list,
                "Usage counts",
                "Once a day, sends Rinth a random number, the version and the build, nothing else. "
                        + "It helps us know how many people play.",
                ClientSettings.USAGE_COUNTS));
        return rows;
    }

    private static Ui.Tile tile(Icons icon, String title, String description, Runnable action) {
        return Ui.tile(icon, Component.literal(title), () -> description, action);
    }

    private ControlList.Row toggle(ControlList list, String label, String tooltip, ClientSettings.Flag flag) {
        var button = Ui.toggle(Component.literal(label), flag.get(), Theme.CONTROL_WIDTH / 2, on -> {
            flag.set(on);
            revision++;
        });
        return list.row(Component.literal(label), tooltip, List.of(button), () -> button.setValue(flag.get()));
    }

    /**
     * The game's own look or Rinth's ({@link Look}): settled as the game starts (the fonts and the
     * loading screen use it first), so the switch is for the next start.
     */
    private ControlList.Row minecraftLook(ControlList list) {
        String label = "Minecraft's look";
        var button = Ui.toggle(Component.literal(label), Look.vanillaNext(), Theme.CONTROL_WIDTH / 2, on -> {
            Look.chooseVanilla(on);
            revision++;
        });
        return list.row(
                Component.literal(label),
                "Minecraft's own menus, font, panorama and loading screen instead of Rinth's. Rinth's"
                        + " screens stay. Takes effect the next time the game starts (reload the page).",
                List.of(button),
                () -> button.setValue(Look.vanillaNext()));
    }

    /** The account's badge setting (ADR 0062): kept by the service, so only while signed in. */
    private ControlList.Row showMyBadge(ControlList list) {
        OnlineService online = RinthOnline.service();
        var button = Ui.toggle(
                Component.literal("Show others I use Rinth"), online.showsBadge(), Theme.CONTROL_WIDTH / 2, on -> {
                    online.setShowBadge(on, result -> revision++);
                });
        Runnable refresh = () -> {
            button.active = online.status() == Online.Status.SIGNED_IN;
            button.setValue(online.showsBadge());
        };
        refresh.run();
        return list.row(
                Component.literal("Show others I use Rinth"),
                "Shows Rinth's badge beside your name to other Rinth players. Needs a Rinth account."
                        + " Turning it off doesn't hide you from friends or messages.",
                List.of(button),
                refresh);
    }

    @Override
    protected long revision() {
        return revision
                + RinthModules.system().revision()
                + RinthOnline.service().revision();
    }

    @Override
    protected void footer(LinearLayout footer) {
        footer.addChild(Ui.button(CommonComponents.GUI_DONE, Theme.FOOTER_BUTTON_WIDTH, this::onClose));
    }

    @Override
    protected void init() {
        super.init();
        titleWidget = null;
        for (var child : children()) {
            if (child instanceof StringWidget text
                    && text.getMessage().getString().equals(TITLE.getString())) {
                titleWidget = text;
                break;
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        AbstractWidget title = titleWidget;
        if (title != null) {
            // Rinth's badge to the title's left, a little taller than the text.
            Icons.RINTH.draw(graphics, title.getX() - 16, title.getY() - 2, 11, Theme.ACCENT);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return parent == null || parent.isPauseScreen();
    }
}
