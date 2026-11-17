package rinth.client.online;

import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import rinth.client.ui.Theme;

/**
 * What's new in a newer Rinth Client, opened from the title screen's update notice: its changelog
 * as the online service gave it, one change a line, and the way to the repository to get it.
 */
final class ReleaseNotesScreen extends FormScreen {

    private final List<String> notes;

    ReleaseNotesScreen(Screen parent, String version, List<String> notes) {
        super("Rinth Client " + version + " is out", parent);
        this.notes = List.copyOf(notes);
    }

    @Override
    protected void build() {
        text("What's new:", Theme.MUTED);
        for (String note : notes) {
            text("\u2022 " + note, Theme.TEXT);
        }
        gap(4);
        row(
                new String[] {"Get It on GitHub", "Back"},
                new Runnable[] {() -> UpdateNotice.openRepository(this), this::onClose});
    }
}
