package com.example.worldprestige;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class PrestigeScreen extends Screen {
    private PrestigeNetwork.StatePacket data;
    private int top;
    private int hintY;

    public PrestigeScreen(PrestigeNetwork.StatePacket data) {
        super(Component.literal("World Prestige"));
        this.data = data;
    }

    public void update(PrestigeNetwork.StatePacket newData) {
        this.data = newData;
        rebuildWidgets();
    }

    @Override protected void init() {
        Upgrade[] ups = Upgrade.values();
        top = Math.max(10, height / 2 - (ups.length * 25 + 85) / 2);
        int x = width / 2 - 150;
        int y = top + 32;
        for (Upgrade u : ups) {
            addUpgradeButton(u, x, y);
            y += 25;
        }
        y += 25; // Add some space between the last upgrade button and the reset button
        addRenderableWidget(Button.builder(Component.literal("ワールドリセット"), b -> minecraft.setScreen(new ResetConfirmScreen())).bounds(x, y, 300, 20).build());
        y += 25;
        addRenderableWidget(Button.builder(Component.literal("閉じる"), b -> onClose()).bounds(x, y, 300, 20).build());
        hintY = y + 32;
    }

    private void addUpgradeButton(Upgrade u, int x, int y) {
        int level = levelOf(u);
        int cost = u.cost(level);
        Component label = Component.literal(u.displayName() + " " + u.effectText() + "  Lv." + level + "(適用" + activeOf(u) + ")  [" + cost + "pt]");
        Button button = Button.builder(label, b -> purchase(u)).bounds(x, y, 300, 20).build();
        button.active = data.points() >= cost;   // 表示上の目安。本当の判定はサーバー側
        addRenderableWidget(button);
    }

    private int levelOf(Upgrade u) {
        int[] lv = data.levels();
        return u.ordinal() < lv.length ? lv[u.ordinal()] : 0;
    }

    /** 実際に効いているレベル(購入しても、ワールドリセットまでは上がらない)。 */
    private int activeOf(Upgrade u) {
        int[] lv = data.active();
        return u.ordinal() < lv.length ? lv[u.ordinal()] : 0;
    }

    private void purchase(Upgrade u) {
        PrestigeNetwork.CHANNEL.sendToServer(new PrestigeNetwork.BuyPacket(u.id()));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, "Prestige Point(全員共通): " + data.points(), width / 2, top, 0xFFFFFF);
        graphics.drawCenteredString(font, "周回数: " + data.laps() + "  (強化はワールドリセット後から適用)", width / 2, top + 13, 0xAAAAAA);
        graphics.drawCenteredString(font, "リセットは OP またはシングルプレイのホストのみ", width / 2, hintY, 0x777777);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
}
