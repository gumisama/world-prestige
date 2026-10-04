package com.example.worldprestige;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * ワールドリセットの確認画面(2 段階)。クライアント専用。
 * 1 回目と 2 回目で「はい」「いいえ」の位置を入れ替えて、続けて押し間違えないようにしている。
 *   1 回目: [はい] [いいえ]   2 回目: [いいえ] [はい]
 */
public class ResetConfirmScreen extends Screen {
    private final int step;   // 1 = 最初の確認, 2 = 最終確認

    public ResetConfirmScreen() { this(1); }

    private ResetConfirmScreen(int step) {
        super(Component.literal("World Reset"));
        this.step = step;
    }

    @Override protected void init() {
        int y = height / 2 + 15;
        int left = width / 2 - 105;
        int right = width / 2 + 5;
        Button yes = Button.builder(Component.literal("はい"), b -> onYes())
                .bounds(step == 1 ? left : right, y, 100, 20).build();
        Button no = Button.builder(Component.literal("いいえ"), b -> onClose())
                .bounds(step == 1 ? right : left, y, 100, 20).build();
        addRenderableWidget(yes);
        addRenderableWidget(no);
    }

    private void onYes() {
        if (step == 1) {
            minecraft.setScreen(new ResetConfirmScreen(2));
        } else {
            PrestigeNetwork.CHANNEL.sendToServer(new PrestigeNetwork.ResetExecutePacket());
            onClose();
        }
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int cx = width / 2;
        int y = height / 2 - 40;
        if (step == 1) {
            g.drawCenteredString(font, "リセットしますか？", cx, y, 0xFFFFFF);
            g.drawCenteredString(font, "アイテムとして放置されているフラグメントは消滅します", cx, y + 16, 0xFFAA00);
            g.drawCenteredString(font, "(ポイント・強化・周回数は引き継がれます)", cx, y + 30, 0x777777);
        } else {
            g.drawCenteredString(font, "本当にリセットしますか？", cx, y, 0xFF5555);
            g.drawCenteredString(font, "ワールドは新しく作り直されます", cx, y + 16, 0xAAAAAA);
            g.drawCenteredString(font, "※「はい」と「いいえ」の位置が入れ替わっています", cx, y + 30, 0x777777);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
}
