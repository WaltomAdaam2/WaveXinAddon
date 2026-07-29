package me.waltom.wavexin.modules.chatfilter;

import java.util.ArrayList;
import java.util.List;

public final class ChatFilterBehaviorTest {
    private ChatFilterBehaviorTest() {
    }

    public static void main(String[] args) {
        assertDeath("f穿衣服 自杀", "plain suicide");
        assertDeath("哈基米嘉豪 在岩浆中自杀", "lava suicide");
        assertDeath("lcarus1225 炸死了自己", "self explosion");
        assertDeath("Xwenkun_china 毒死了自己", "self poison");
        assertDeath("王元鹏 被 箫雨凌普 的荆棘反杀", "thorns");
        assertDeath("铅笔灰 被 清朝皇帝玄烨 用 末影水晶 炸死", "crystal kill");
        assertDeath("王元鹏 被 箫雨凌普 用 item.minecraft.stone_axe 击杀", "weapon kill");
        assertDeath("决明苗2b2t小号 被 所有 推下悬崖而死亡", "fall by player");
        assertDeath("阿尔法彬 被 大6 推到了虚空", "void by player");
        assertDeath("夏冲日出蝉鸣 被 缺物资kit加q3775733955 用岩浆烧死", "lava by player");
        assertDeath("根本不一样吧 was impaled by give我是人", "english impaled");
        assertDeath("Alice 使用床炸死自己", "bed self explosion");
        assertDeath("Alice 用烟花炸死了自己", "firework self explosion");
        assertDeath("Alice 被 坠落的物品砸死", "falling block death");
        assertDeath("Alice 被 Zombie 窒息而死", "suffocated by entity");
        assertDeath("Alice 被 Bob 用火球击杀", "fireball by player");
        assertDeath("Alice 被自己的三叉戟射死", "own trident death");
        assertDeath("Alice 跳出世界边界而自杀", "world border suicide");
        assertDeath("Alice 在扔末影珍珠时死亡", "ender pearl death");
        assertDeath("Alice 被自己扔出的鸡蛋打死", "own egg death");

        assertNotDeath("[WaveXin] Created Xaero waypoint: Base 1", "waypoint message");
        assertNotDeath("[WaveXin] Base found! Chunk: 50332, 50469", "base found message");
        assertNotDeath("Server restarting in 5 minutes", "server notice");
        assertNotDeath("<player> hello world", "public chat");
        assertEquals("Alice", ChatFilter.privateMessagePlayer("From Alice: hi"), "from private player");
        assertEquals("Bob", ChatFilter.privateMessagePlayer("Bob whispers: hi"), "whisper private player");
        assertEquals("小明", ChatFilter.privateMessagePlayer("来自 小明: 你好"), "chinese private player");
        assertEquals("Alice", ChatFilter.publicMessagePlayer("<Alice> hello"), "angle public player");
        assertEquals("Bob", ChatFilter.publicMessagePlayer("Bob: hello"), "colon public player");
        assertNull(ChatFilter.publicMessagePlayer("使用方式: /S <%command_name%> <%command_name%>"), "usage server message");
        assertNull(ChatFilter.publicMessagePlayer("[Server: 已将某玩家设置为服务器管理员]"), "server operator message");
        assertNull(ChatFilter.publicMessagePlayer("系统: 正在保存世界"), "system server message");
        assertNull(ChatFilter.publicMessagePlayer("Warning: reconnect soon"), "warning server message");
        assertNull(ChatFilter.publicMessagePlayer("Error: connection failed"), "error server message");
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
