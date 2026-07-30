package me.waltom.wavexin.modules.chatfilter;

import java.util.ArrayList;
import java.util.List;

public final class ChatFilterBehaviorTest {
    private ChatFilterBehaviorTest() {
    }

    public static void main(String[] args) {
        List<String> supportedDeaths = List.of(
            "f穿衣服 自杀",
            "哈基米嘉豪 在岩浆中自杀",
            "lcarus1225 炸死了自己",
            "Xwenkun_china 毒死了自己",
            "王元鹏 被 箫雨凌普 的荆棘反杀",
            "铅笔灰 被 清朝皇帝玄烨 用 末影水晶 炸死",
            "王元鹏 被 箫雨凌普 用 item.minecraft.stone_axe 击杀",
            "决明苗2b2t小号 被 所有 推下悬崖而死亡",
            "阿尔法彬 被 大6 推到了虚空",
            "夏冲日出蝉鸣 被 缺物资kit加q3775733955 用岩浆烧死",
            "根本不一样吧 was impaled by give我是人",
            "Alice 使用床炸死自己",
            "Alice 用烟花炸死了自己",
            "Alice 被 坠落的物品砸死",
            "Alice 被 Zombie 窒息而死",
            "Alice 被 Bob 用火球击杀",
            "Alice 被自己的三叉戟射死",
            "Alice 跳出世界边界而自杀",
            "Alice 在扔末影珍珠时死亡",
            "Alice 被自己扔出的鸡蛋打死"
        );
        for (String message : supportedDeaths) {
            assertDeath(message, "supported death");
            assertNull(ChatFilter.publicMessagePlayer(message), "death is not public chat");
        }

        assertNotDeath("[WaveXin] Created Xaero waypoint: Base 1", "waypoint message");
        assertNotDeath("[WaveXin] Base found! Chunk: 50332, 50469", "base found message");
        assertNotDeath("Server restarting in 5 minutes", "server notice");
        assertNotDeath("<player> hello world", "public chat");

        assertEquals("Alice", ChatFilter.privateMessagePlayer("From Alice: hi"), "from private player");
        assertEquals("Bob", ChatFilter.privateMessagePlayer("Bob whispers: hi"), "whisper private player");
        assertEquals("小明", ChatFilter.privateMessagePlayer("来自 小明: 你好"), "chinese private player");
        assertNull(ChatFilter.publicMessagePlayer("From Alice: hi"), "private from is not public");
        assertNull(ChatFilter.publicMessagePlayer("Bob whispers: hi"), "private whisper is not public");
        assertNull(ChatFilter.publicMessagePlayer("来自 小明: 你好"), "private chinese is not public");

        assertEquals("Alice", ChatFilter.publicMessagePlayer("<Alice> hello"), "angle public player");
        assertEquals("Alice_123", ChatFilter.publicMessagePlayer("<Alice_123> hello world"), "angle public player with underscore");
        assertNull(ChatFilter.publicMessagePlayer("Bob: hello"), "colon public format is not verified public chat");
        assertNull(ChatFilter.publicMessagePlayer("来自 : 10"), "lookup output count");
        assertNull(ChatFilter.publicMessagePlayer("来自 : 0.999"), "lookup output decimal");
        assertNull(ChatFilter.publicMessagePlayer("玩家名称: Alice"), "player lookup output");
        assertNull(ChatFilter.publicMessagePlayer("使用方式: /command"), "usage server message");
        assertNull(ChatFilter.publicMessagePlayer("[Server: 已将某玩家设置为服务器管理员]"), "server operator message");
        assertNull(ChatFilter.publicMessagePlayer("系统: 正在保存世界"), "system server message");
        assertNull(ChatFilter.publicMessagePlayer("Server: restarting"), "server colon message");
        assertNull(ChatFilter.publicMessagePlayer("Warning: reconnect soon"), "warning server message");
        assertNull(ChatFilter.publicMessagePlayer("Error: connection failed"), "error server message");
        assertNull(ChatFilter.publicMessagePlayer(""), "empty message");
        assertNull(ChatFilter.publicMessagePlayer("<Alice> hello\n<Bob> hello"), "multiline public text is not one message");

        assertTrue(ChatFilter.shouldHidePublicMessage("<Bob> hello", List.of(), true, "Alice", "Alice"), "non-allowlisted public hidden");
        assertFalse(ChatFilter.shouldHidePublicMessage("<Bob> hello", List.of("bob"), true, "Alice", "Alice"), "public allowlist is case-insensitive");
        assertFalse(ChatFilter.shouldHidePublicMessage("<Alice> hello", List.of(), true, "Alice", "Alice"), "own account public shown");
        assertFalse(ChatFilter.shouldHidePublicMessage("<Alice> hello", List.of(), true, "Other", "§a[VIP] Alice"), "own display public shown");
        assertTrue(ChatFilter.shouldHidePublicMessage("<Alice> hello", List.of(), false, "Alice", "Alice"), "own public can be hidden when disabled");
        assertFalse(ChatFilter.shouldHidePublicMessage("玩家名称: Alice", List.of(), true, "Alice", "Alice"), "server colon output is not hidden as public");

        assertEquals("Alice", ChatFilter.normalizePlayerName("§a[VIP] Alice"), "display prefix stripping");
        assertTrue(ChatFilter.playerListContains(List.of("Alice"), "alice"), "case-insensitive allowlist");
        assertTrue(ChatFilter.samePlayerName("Alice", "alice"), "case-insensitive own player");
        assertFalse(ChatFilter.samePlayerName("Alice", "Bob"), "different own player");
        assertFalse(ChatFilter.playerListContains(List.of("Alice"), "Bob"), "different allowlist player");

        ArrayList<String> players = new ArrayList<>();
        assertTrue(ChatFilter.addPlayerName(players, "Alice"), "add allowlist player");
        assertFalse(ChatFilter.addPlayerName(players, "alice"), "skip duplicate allowlist player");
    }

    private static void assertTrue(boolean actual, String label) {
        if (!actual) throw new AssertionError(label + " should be true");
    }

    private static void assertFalse(boolean actual, String label) {
        if (actual) throw new AssertionError(label + " should be false");
    }

    private static void assertNull(String actual, String label) {
        if (actual != null) throw new AssertionError(label + " should be null but got [" + actual + "]");
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected [" + expected + "] but got [" + actual + "]");
        }
    }

    private static void assertDeath(String message, String label) {
        if (!ChatFilter.isDeathMessage(ChatFilter.normalize(message))) {
            throw new AssertionError(label + " should be treated as a death message: " + message);
        }
    }

    private static void assertNotDeath(String message, String label) {
        if (ChatFilter.isDeathMessage(ChatFilter.normalize(message))) {
            throw new AssertionError(label + " should not be treated as a death message: " + message);
        }
    }
}