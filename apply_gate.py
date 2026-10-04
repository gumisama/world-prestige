"""自動ポイント変換を「強化を買うまで使えない」にするパッチ。
リポジトリのルート(src フォルダがある場所)に置いて実行: python apply_gate.py
何回実行しても安全(適用済みの箇所はスキップ)。"""
import pathlib
import re
import sys

D = pathlib.Path("src/main/java/com/example/worldprestige")
if not D.exists():
    sys.exit("リポジトリのルート(src フォルダがある場所)で実行してください。")


def edit(name, pairs):
    p = D / name
    s = p.read_bytes().decode("utf-8")
    crlf = "\r\n" in s
    s = s.replace("\r\n", "\n")
    applied = 0
    print(name)
    for old, new in pairs:
        if new in s:
            print("  スキップ(適用済み):", old[:45].strip())
        elif old not in s:
            print("  !! 見つからない(手動で直す):", old[:45].strip())
        else:
            s = s.replace(old, new, 1)
            applied += 1
            print("  OK:", old[:45].strip())
    if crlf:
        s = s.replace("\n", "\r\n")
    p.write_bytes(s.encode("utf-8"))
    return s, applied


# ---- FragmentGeneratorBlockEntity ----
edit("FragmentGeneratorBlockEntity.java", [
    ("    public void setAutoConvert(boolean value) {\n        autoConvert = value;\n",
     "    private static boolean autoUnlocked() { return SharedPrestige.getActiveLevel(Upgrade.AUTO_CONVERT) > 0; }\n\n"
     "    public void setAutoConvert(boolean value) {\n        autoConvert = value && autoUnlocked();\n"),
    ("if (autoConvert && fragments > 0", "if (autoConvert && autoUnlocked() && fragments > 0"),
    ("measured, autoConvert);", "measured, autoConvert, autoUnlocked());"),
])

# ---- PrestigeNetwork ----
s, n = edit("PrestigeNetwork.java", [
    ("long measured, boolean auto) {}", "long measured, boolean auto, boolean autoUnlocked) {}"),
    ("b.writeBoolean(m.auto());",
     "b.writeBoolean(m.auto());\n                    b.writeBoolean(m.autoUnlocked());"),
    ("b.readBoolean(), b.readLong(), b.readBoolean()),",
     "b.readBoolean(), b.readLong(), b.readBoolean(), b.readBoolean()),"),
    ("購入できません。ポイントが足りません。", "購入できません(ポイント不足、または購入済み)。"),
])
if n >= 3:  # パケットの形が変わったときだけ VERSION を 1 つ上げる
    p = D / "PrestigeNetwork.java"
    raw = p.read_bytes().decode("utf-8")
    m = re.search(r'VERSION = "(\d+)"', raw)
    if m:
        raw = raw.replace(m.group(0), 'VERSION = "%d"' % (int(m.group(1)) + 1), 1)
        p.write_bytes(raw.encode("utf-8"))
        print("  VERSION を %s -> %d に更新" % (m.group(1), int(m.group(1)) + 1))

# ---- FragmentGeneratorScreen ----
edit("FragmentGeneratorScreen.java", [
    ('return Component.literal(data.auto() ? "自動変換: オン" : "自動変換: オフ");',
     'return Component.literal(!data.autoUnlocked() ? "自動変換: 未解放" : data.auto() ? "自動変換: オン" : "自動変換: オフ");'),
    ("if (autoButton != null) autoButton.setMessage(autoLabel());",
     "if (autoButton != null) autoButton.setMessage(autoLabel());\n        if (autoButton != null) autoButton.active = data.autoUnlocked();"),
    ("addRenderableWidget(autoButton);",
     "autoButton.active = data.autoUnlocked();\n        addRenderableWidget(autoButton);"),
])

print("\n完了。「!!」が出た箇所があれば、その行を貼ってください。なければ git add . → commit → push。")
