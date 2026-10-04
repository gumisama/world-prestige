package com.example.worldprestige;

import net.minecraft.client.Minecraft;

/** クライアント専用の処理。DistExecutor 経由でのみ呼ばれる。 */
public final class PrestigeClient {
    private PrestigeClient() {}

    public static void receive(PrestigeNetwork.StatePacket data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PrestigeScreen screen) {
            screen.update(data);
        } else if (data.open()) {
            mc.setScreen(new PrestigeScreen(data));
        }
    }

    public static void receiveResetPrompt() {
        Minecraft.getInstance().setScreen(new ResetConfirmScreen());
    }

    public static void receiveGenerator(PrestigeNetwork.GeneratorPacket data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof FragmentGeneratorScreen screen && screen.isFor(data.pos())) {
            screen.update(data);
        } else if (data.open()) {
            mc.setScreen(new FragmentGeneratorScreen(data));
        }
    }
}
