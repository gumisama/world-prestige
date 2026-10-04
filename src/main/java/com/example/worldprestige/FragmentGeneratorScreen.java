package com.example.worldprestige;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** World Fragment Generator の設定画面。入力速度(FE/t)を好きな数字で指定できる。クライアント専用。 */
public class FragmentGeneratorScreen extends Screen {
    private PrestigeNetwork.GeneratorPacket data;
    private EditBox speedBox;
    private Button limitButton;
    private Button autoButton;
    private int top;
    private int refreshTimer;

    public FragmentGeneratorScreen(PrestigeNetwork.GeneratorPacket data) {
        super(Component.literal("World Fragment Generator"));
        this.data = data;
    }

    public boolean isFor(BlockPos pos) { return data.pos().equals(pos); }

    /** 状態だけ差し替える(入力欄は作り直さない = 入力中の文字が消えない)。 */
    public void update(PrestigeNetwork.GeneratorPacket newData) {
        this.data = newData;
        if (limitButton != null) limitButton.setMessage(limitLabel());
        if (autoButton != null) autoButton.setMessage(autoLabel());
    }

    private Component limitLabel() {
        return Component.literal(data.unlimited() ? "入力上限: なし(無制限)  ← 押すと上限ありに戻す" : "入力上限: あり  ← 押すと無制限にする");
    }
    private Component autoLabel() {
        return Component.literal(data.auto() ? "自動変換: オン" : "自動変換: オフ");
    }

    @Override protected void init() {
        top = Math.max(10, height / 2 - 110);
        int x = width / 2 - 110;
        int y = top + 30;
        speedBox = new EditBox(font, x, y, 150, 20, Component.literal("入力速度"));
        speedBox.setMaxLength(19);   // long の最大値は 19 桁
        speedBox.setFilter(s -> s.matches("\\d*"));
        speedBox.setValue(Long.toString(data.speed()));
        addRenderableWidget(speedBox);
        setInitialFocus(speedBox);
        addRenderableWidget(Button.builder(Component.literal("設定"), b -> applySpeed())
                .bounds(x + 155, y, 65, 20).build());
        limitButton = Button.builder(limitLabel(),
                b -> send(PrestigeNetwork.ACTION_SET_UNLIMITED, data.unlimited() ? 0 : 1)).bounds(x, y + 25, 220, 20).build();
        addRenderableWidget(limitButton);
        addRenderableWidget(Button.builder(Component.literal("全部ポイントに変換"),
                b -> send(PrestigeNetwork.ACTION_CONVERT, 0)).bounds(x, y + 135, 108, 20).build());
        autoButton = Button.builder(autoLabel(),
                b -> send(PrestigeNetwork.ACTION_SET_AUTO, data.auto() ? 0 : 1)).bounds(x + 112, y + 135, 108, 20).build();
        addRenderableWidget(autoButton);
        addRenderableWidget(Button.builder(Component.literal("World Fragment をアイテムで取り出す"),
                b -> send(PrestigeNetwork.ACTION_TAKE, 0)).bounds(x, y + 160, 220, 20).build());
        addRenderableWidget(Button.builder(Component.literal("閉じる"), b -> onClose())
                .bounds(x, y + 185, 220, 20).build());
    }

    private void applySpeed() {
        String text = speedBox.getValue();
        long speed;
        try {
            speed = text.isEmpty() ? 0L : Long.parseLong(text);
        } catch (NumberFormatException ex) {
            speed = Long.MAX_VALUE;   // 19 桁で long を超えた場合は最大値
        }
        speedBox.setValue(Long.toString(speed));
        send(PrestigeNetwork.ACTION_SET_SPEED, speed);
    }

    private void send(int action, long value) {
        PrestigeNetwork.CHANNEL.sendToServer(new PrestigeNetwork.GeneratorActionPacket(data.pos(), action, value));
    }

    @Override public void tick() {
        super.tick();
        if (++refreshTimer >= 10) {      // 0.5 秒ごとに状態を取り直す
            refreshTimer = 0;
            send(PrestigeNetwork.ACTION_REFRESH, 0);
        }
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (speedBox != null && speedBox.isFocused() && (keyCode == 257 || keyCode == 335)) {   // Enter / テンキー Enter
            applySpeed();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 実測入力と次の 1 個の必要電力から、今のペースで 1 秒に何個作れるかの目安。 */
    private String rateText() {
        if (data.measured() <= 0 || data.cost() <= 0) return "入力なし";
        double perSecond = (double) data.measured() * 20.0 / (double) data.cost();
        return String.format(Locale.ROOT, "今のペースで約 %,.2f 個/秒", perSecond);
    }

    private static String fmt(long n) { return String.format(Locale.ROOT, "%,d", n); }
    private static String fmt(java.math.BigInteger n) { return String.format(Locale.ROOT, "%,d", n); }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int cx = width / 2;
        g.drawCenteredString(font, "World Fragment Generator", cx, top, 0xFFFFFF);
        g.drawCenteredString(font, "入力速度 (FE/tick) を入力して「設定」", cx, top + 17, 0xAAAAAA);
        int y = top + 88;
        g.drawCenteredString(font, data.unlimited() ? "現在の入力速度: 無制限(隣接する電力ブロックからも吸い出す)" : "現在の入力速度: " + fmt(data.speed()) + " FE/t  (" + fmt(java.math.BigInteger.valueOf(data.speed()).multiply(java.math.BigInteger.valueOf(20))) + " FE/秒)", cx, y, 0x55FF55);
        g.drawCenteredString(font, "次の1個に必要: " + fmt(data.cost()) + " FE", cx, y + 13, 0xFFFFFF);
        g.drawCenteredString(font, "消費済み: " + fmt(data.progress()) + " / " + fmt(data.cost()) + " FE", cx, y + 26, 0xFFFFFF);
        g.drawCenteredString(font, "実測入力: " + fmt(data.measured()) + " FE/t  (" + rateText() + ")", cx, y + 39, 0x55FFFF);
        g.drawCenteredString(font, "機械の中: " + fmt(data.stored()) + " 個  /  累計生成: " + fmt(data.made()) + " 個", cx, y + 52, 0xAAAAAA);
        g.drawCenteredString(font, "受け取った電力は全部消費され、消費した分から作られます", cx, y + 65, 0x777777);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override public boolean isPauseScreen() { return false; }
}
