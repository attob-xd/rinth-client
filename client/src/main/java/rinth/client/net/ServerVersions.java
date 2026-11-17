package rinth.client.net;

import rinth.via.RinthVia;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * Which Minecraft version the game speaks to a server (ADR 0017). Each server in the list
 * has a choice, saved with it: automatic (the default) or one release. Automatic means the version
 * the server reports, or else the one an earlier join found; a server that doesn't say (Eagler
 * servers' MOTDs never do) is joined as the game's own version, and a refusal names the right one
 * ({@link VersionRetry}, ADR 0018). Via translates whenever the choice isn't the game's own version.
 */
public final class ServerVersions {

    /** The saved choice for "work it out". */
    public static final int AUTO = -1;

    /**
     * The server list's protocol for a server that didn't say which version it speaks (an Eagler
     * server): joinable, and "Auto" finds the version when it joins.
     */
    public static final int UNKNOWN = -2;

    private static final String TAG = "rinthVersion";

    private ServerVersions() {}

    /** The game's own protocol. */
    public static int gameProtocol() {
        return SharedConstants.getCurrentVersion().protocolVersion();
    }

    /** {@code ServerData.write}: saves a non-automatic choice. */
    public static void write(ServerData server, CompoundTag tag) {
        if (server.rinthVersion != AUTO) {
            tag.putInt(TAG, server.rinthVersion);
        }
    }

    /** {@code ServerData.read}. */
    public static void read(ServerData server, CompoundTag tag) {
        server.rinthVersion = tag.getIntOr(TAG, AUTO);
    }

    /**
     * Whether the game can join a server whose ping reported {@code protocol}: its own version, or
     * one Via translates. The server list marks the rest incompatible.
     */
    public static boolean isCompatible(int protocol) {
        return protocol == UNKNOWN || protocol == gameProtocol() || RinthVia.supports(protocol);
    }

    /** A version's name for the player: "1.8.x", "26.3". */
    public static String name(int protocol) {
        if (protocol == UNKNOWN) {
            return "Auto";
        }
        if (protocol == gameProtocol()) {
            return SharedConstants.getCurrentVersion().name();
        }
        String name = RinthVia.versionName(protocol);
        return name != null ? name : "protocol " + protocol;
    }

    /**
     * The edit-server screen's version button: shows the choice, automatic or one release, and
     * opens the list of them ({@link VersionSelectScreen}), which saves the pick into {@code server}.
     */
    public static Button button(ServerData server, int x, int y) {
        List<VersionSelectScreen.Choice> choices = new ArrayList<>();
        choices.add(new VersionSelectScreen.Choice(AUTO, "Auto", "the server's own version"));
        for (RinthVia.Release release : RinthVia.releases()) {
            String note = release.protocol() == gameProtocol() ? "native" : "";
            choices.add(new VersionSelectScreen.Choice(release.protocol(), release.name(), note));
        }
        if (choices.stream().noneMatch(choice -> choice.protocol() == server.rinthVersion)) {
            server.rinthVersion = AUTO;
        }
        return Button.builder(label(server.rinthVersion), pressed -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    Screen editScreen = minecraft.gui.screen();
                    if (editScreen != null) {
                        minecraft.gui.setScreen(
                                new VersionSelectScreen(editScreen, choices, server.rinthVersion, protocol -> {
                                    server.rinthVersion = protocol;
                                    pressed.setMessage(label(protocol));
                                }));
                    }
                })
                .bounds(x, y, 200, 20)
                .build();
    }

    // Literal English until Rinth has its own language files.
    private static Component label(int protocol) {
        return Component.literal("Version: " + (protocol == AUTO ? "Auto" : name(protocol)));
    }
}
