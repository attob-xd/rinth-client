package rinth.features.shulkertooltips;

import static org.assertj.core.api.Assertions.assertThat;

import rinth.framework.event.ContainerTooltip;
import rinth.framework.setting.BooleanSetting;
import rinth.framework.testing.TestGame;
import org.junit.jupiter.api.Test;

class ShulkerTooltipsTest {

    private final ShulkerTooltips module = new ShulkerTooltips();
    private final TestGame game = new TestGame(module);

    @Test
    void previewsInTheBoxsColor() {
        module.setEnabled(true);
        ContainerTooltip tooltip = game.containerTooltip(false);
        assertThat(tooltip.preview()).isTrue();
        assertThat(tooltip.tinted()).isTrue();
    }

    @Test
    void canWaitForShift() {
        module.setEnabled(true);
        ((BooleanSetting) module.settings().get("only-with-shift")).set(true);
        assertThat(game.containerTooltip(false).preview()).isFalse();
        assertThat(game.containerTooltip(true).preview()).isTrue();
    }

    @Test
    void offLeavesTheGamesLines() {
        assertThat(game.containerTooltip(true).preview()).isFalse();
    }
}
