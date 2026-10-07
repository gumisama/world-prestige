package com.example.worldprestige;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 進捗画面ふうの強化 GUI。左のルート(World Fragment)から、強化ごとのノードが枝分かれする。
 * ノードにはアイコンとレベルだけを出し、説明はホバー(ツールチップ)にだけ出す。
 * ノードをクリックすると購入。クライアント専用。
 * 見た目はバニラの進捗画面のテクスチャ(window / widgets / backgrounds)を借りている。
 * 強化を増やしても、この画面の変更は不要(Upgrade.java に 1 行足すだけ)。
 */
public class PrestigeScreen extends Screen {
    private static final ResourceLocation WINDOW = new ResourceLocation("textures/gui/advancements/window.png");
    private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/advancements/widgets.png");
    private static final ResourceLocation BACKGROUND = new ResourceLocation("textures/gui/advancements/backgrounds/stone.png");

    // ================= レイアウト(ここを書き換えて調整) =================
    private static final int WIN_W = 252, WIN_H = 140;            // ウィンドウ全体(バニラ進捗画面と同じ)
    private static final int IN_X = 9, IN_Y = 18, IN_W = 234, IN_H = 113;   // 中の描画領域
    private static final int NODE = 26;                           // ノードの大きさ
    private static final int ROOT_X = 12;                         // ルートの位置(中の領域の左から)
    private static final int CHILD_X = 72;                        // 強化ノードの位置(同上)
    private static final int GAP = 34;                            // 強化ノードの縦の間隔
    /** レベルがこの値以上でノードの枠が変わる(GOAL 枠 / CHALLENGE 枠)。 */
    private static final int GOAL_LEVEL = 10, CHALLENGE_LEVEL = 25;
    // =====================================================================

    private PrestigeNetwork.StatePacket data;
    private double scroll;

    public PrestigeScreen(PrestigeNetwork.StatePacket data) {
        super(Component.literal("World Prestige"));
        this.data = data;
    }

    /** サーバーから新しい状態が届いたとき。毎フレーム data を読んで描くので、差し替えるだけでよい。 */
    public void update(PrestigeNetwork.StatePacket newData) {
        this.data = newData;
    }

    private int winX() { return (width - WIN_W) / 2; }
    private int winY() { return Math.max(4, (height - WIN_H - 26) / 2); }

    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.literal("ワールドリセット"),
                b -> minecraft.setScreen(new ResetConfirmScreen()))
                .bounds(width / 2 - 60, winY() + WIN_H + 4, 120, 20).build());
    }

    // ---------------- 値の取り出し ----------------

    private int levelOf(Upgrade u) {
        int[] lv = data.levels();
        return u.ordinal() < lv.length ? lv[u.ordinal()] : 0;
    }

    private int activeOf(Upgrade u) {
        int[] lv = data.active();
        return u.ordinal() < lv.length ? lv[u.ordinal()] : 0;
    }

    private boolean maxed(Upgrade u) { return u.maxLevel() > 0 && levelOf(u) >= u.maxLevel(); }

    // ---------------- ノードの位置 ----------------

    private int rootX() { return winX() + IN_X + ROOT_X; }
    private int childX() { return winX() + IN_X + CHILD_X; }

    private int totalHeight() { return (Upgrade.values().length - 1) * GAP + NODE; }
    private int maxScroll() { return Math.max(0, totalHeight() + 8 - IN_H); }

    /** 先頭ノードの上端 y(スクロール反映済み)。収まるときは中央寄せ。 */
    private int topY() {
        int pad = totalHeight() + 8 >= IN_H ? 4 : (IN_H - totalHeight()) / 2;
        return winY() + IN_Y + pad - (int) scroll;
    }
    private int rootY() { return topY() + totalHeight() / 2 - NODE / 2; }
    private int childY(int index) { return topY() + index * GAP; }

    private boolean inArea(double mx, double my) {
        int ix = winX() + IN_X, iy = winY() + IN_Y;
        return mx >= ix && mx < ix + IN_W && my >= iy && my < iy + IN_H;
    }

    private Upgrade upgradeAt(double mx, double my) {
        if (!inArea(mx, my)) return null;
        Upgrade[] ups = Upgrade.values();
        for (int i = 0; i < ups.length; i++) {
            int x = childX(), y = childY(i);
            if (mx >= x && mx < x + NODE && my >= y && my < y + NODE) return ups[i];
        }
        return null;
    }

    private boolean overRoot(double mx, double my) {
        if (!inArea(mx, my)) return false;
        return mx >= rootX() && mx < rootX() + NODE && my >= rootY() && my < rootY() + NODE;
    }

    // ---------------- 操作 ----------------

    @Override public boolean mouseScrolled(double mx, double my, double delta) {
        if (maxScroll() > 0) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - delta * 16));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            Upgrade hit = upgradeAt(mx, my);
            if (hit != null) {
                if (!maxed(hit) && data.points() >= hit.cost(levelOf(hit))) {   // 表示上の目安。本当の判定はサーバー側
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    PrestigeNetwork.CHANNEL.sendToServer(new PrestigeNetwork.BuyPacket(hit.id()));
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    // ---------------- 描画 ----------------

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int wx = winX(), wy = winY();
        int ix = wx + IN_X, iy = wy + IN_Y;
        scroll = Math.min(scroll, maxScroll());
        g.enableScissor(ix, iy, ix + IN_W, iy + IN_H);

        // 背景タイル(16x16 の石)
        for (int ty = 0; ty < IN_H; ty += 16) {
            for (int tx = 0; tx < IN_W; tx += 16) {
                g.blit(BACKGROUND, ix + tx, iy + ty, 0.0F, 0.0F,
                        Math.min(16, IN_W - tx), Math.min(16, IN_H - ty), 16, 16);
            }
        }

        // 線(ノードの下に描く)
        Upgrade[] ups = Upgrade.values();
        int rx = rootX() + NODE, ry = rootY() + NODE / 2;
        int midX = rx + (childX() - rx) / 2;
        for (int i = 0; i < ups.length; i++) {
            int cy = childY(i) + NODE / 2;
            int color = levelOf(ups[i]) > 0 ? 0xFFFFFFFF : 0xFF808080;
            hLine(g, rx, midX, ry, color);
            vLine(g, midX, ry, cy, color);
            hLine(g, midX, childX(), cy, color);
        }

        // ルート
        drawFrame(g, rootX(), rootY(), 0, true);
        g.renderFakeItem(new ItemStack(ModRegistry.WORLD_FRAGMENT.get()), rootX() + 5, rootY() + 5);

        // 強化ノード(アイコンとレベルだけ。説明はホバー)
        for (int i = 0; i < ups.length; i++) {
            Upgrade u = ups[i];
            int lv = levelOf(u);
            int x = childX(), y = childY(i);
            int frame = lv >= CHALLENGE_LEVEL ? 26 : lv >= GOAL_LEVEL ? 52 : 0;
            drawFrame(g, x, y, frame, lv > 0);
            g.renderFakeItem(u.icon(), x + 5, y + 5);
            if (u.maxLevel() != 1 && lv > 0) {   // 1 回きりの強化はレベル表示なし
                String s = String.valueOf(lv);
                g.pose().pushPose();
                g.pose().translate(0.0F, 0.0F, 200.0F);   // アイコンより手前に出す
                g.drawString(font, s, x + NODE - 3 - font.width(s), y + NODE - 11, 0xFFFFFF, true);
                g.pose().popPose();
            }
        }

        g.disableScissor();

        // ウィンドウ枠と上部の文字
        g.blit(WINDOW, wx, wy, 0, 0, WIN_W, WIN_H);
        g.drawString(font, "World Prestige", wx + 8, wy + 6, 4210752, false);
        String info = "ポイント " + data.points() + "  周回 " + data.laps();
        g.drawString(font, info, wx + WIN_W - 8 - font.width(info), wy + 6, 4210752, false);

        super.render(g, mouseX, mouseY, partialTick);

        // ツールチップ(最後に描く)
        Upgrade hover = upgradeAt(mouseX, mouseY);
        if (hover != null) {
            g.renderComponentTooltip(font, tooltipOf(hover), mouseX, mouseY);
        } else if (overRoot(mouseX, mouseY)) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("Prestige Point(全員共通): " + data.points()));
            lines.add(Component.literal("周回数: " + data.laps()).withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("World Fragment を使うとポイントが増える").withStyle(ChatFormatting.DARK_GRAY));
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    private List<Component> tooltipOf(Upgrade u) {
        int lv = levelOf(u);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(u.displayName()));
        lines.add(Component.literal(u.description()).withStyle(ChatFormatting.GRAY));
        if (u.perLevel() > 0) lines.add(Component.literal("効果: " + u.effectText() + " / Lv").withStyle(ChatFormatting.GRAY));
        if (u.maxLevel() == 1) {
            lines.add(Component.literal(lv > 0 ? "購入済み" : "1 回のみ購入できる")
                    .withStyle(lv > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        } else {
            lines.add(Component.literal("購入済み Lv." + lv + " / 適用中 Lv." + activeOf(u)).withStyle(ChatFormatting.GRAY));
        }
        if (!maxed(u)) {
            int cost = u.cost(lv);
            boolean ok = data.points() >= cost;
            lines.add(Component.literal("次のレベル: " + cost + "pt").withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
            lines.add(Component.literal("購入分は次のワールドリセット後に適用").withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.literal(ok ? "クリックで購入" : "ポイントが足りません")
                    .withStyle(ok ? ChatFormatting.YELLOW : ChatFormatting.RED));
        } else {
            lines.add(Component.literal("購入分は次のワールドリセット後に適用").withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    /** 進捗画面のノード枠。frameU: 0=TASK, 26=CHALLENGE, 52=GOAL。obtained=false なら暗い枠。 */
    private void drawFrame(GuiGraphics g, int x, int y, int frameU, boolean obtained) {
        g.blit(WIDGETS, x, y, frameU, 128 + (obtained ? 0 : NODE), NODE, NODE);
    }

    /** 黒い縁取りつきの線(進捗画面の線と同じ見た目)。 */
    private static void hLine(GuiGraphics g, int x1, int x2, int y, int color) {
        int a = Math.min(x1, x2), b = Math.max(x1, x2);
        g.hLine(a, b, y - 1, 0xFF000000);
        g.hLine(a, b, y + 1, 0xFF000000);
        g.hLine(a, b, y, color);
    }

    private static void vLine(GuiGraphics g, int x, int y1, int y2, int color) {
        int a = Math.min(y1, y2), b = Math.max(y1, y2);
        g.vLine(x - 1, a, b, 0xFF000000);
        g.vLine(x + 1, a, b, 0xFF000000);
        g.vLine(x, a, b, color);
    }

    @Override public boolean isPauseScreen() { return false; }
}
