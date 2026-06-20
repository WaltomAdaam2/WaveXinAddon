package me.waltom.wavexin;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.ServerConnectBeginEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

public class AutoLoginXin extends Module {
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    public static AutoLoginXin INSTANCE;
    private final Timer queueTimer = new Timer();
    private final Timer timer = new Timer();
    private final Timer containerTimer = new Timer();
    private boolean login = false;

    private final Setting<String> password = sgGeneral.add(new StringSetting.Builder()
            .name("Login Password")
            .description("Login password for the Xin server")
            .defaultValue("123456")
            .build());

    public final Setting<Integer> afterLoginTime = sgGeneral.add(new IntSetting.Builder()
            .name("Login Delay")
            .description("Seconds to wait before sending the login password")
            .defaultValue(2)
            .min(0)
            .max(10)
            .sliderMin(0)
            .sliderMax(10)
            .build());

    public final Setting<Integer> joinQueueDelay = sgGeneral.add(new IntSetting.Builder()
            .name("Queue Join Delay")
            .description("Seconds to wait before using the compass to join the queue")
            .defaultValue(2)
            .min(0)
            .max(10)
            .sliderMin(0)
            .sliderMax(10)
            .build());

    public final Setting<Integer> containerClickDelay = sgGeneral.add(new IntSetting.Builder()
            .name("Container Click Delay")
            .description("Seconds to wait before clicking the compass in a container")
            .defaultValue(2)
            .min(0)
            .max(10)
            .sliderMin(0)
            .sliderMax(10)
            .build());

    public AutoLoginXin() {
        super(WaveXinAddon.CATEGORY, "auto-login-xin", "auto-login-xin");
        INSTANCE = this;
        MeteorClient.EVENT_BUS.subscribe(new StaticListener());
    }

    private boolean isInLoginLobby() {
        if (mc.player == null)
            return false;
        var pos = mc.player.getBlockPos();
        return pos.getX() == 8 && pos.getY() == 5 && pos.getZ() == 8;
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        if (!isActive()) {
            resetState();
            return;
        }

        if (mc.player == null || mc.world == null || mc.interactionManager == null)
            return;

        if (login && mc.getNetworkHandler() != null && timer.passedS(afterLoginTime.get())) {
            mc.getNetworkHandler().sendChatCommand("login " + password.get());
            login = false;
        }

        
        if (isInLoginLobby()) {
            
            if (mc.currentScreen instanceof GenericContainerScreen
                    && containerTimer.passedS(containerClickDelay.get())) {
                GenericContainerScreen containerScreen = (GenericContainerScreen) mc.currentScreen;
                var handler = containerScreen.getScreenHandler();

                
                for (int i = 0; i < handler.slots.size(); i++) {
                    var slot = handler.slots.get(i);
                    if (slot.hasStack() && slot.getStack().getItem() == Items.COMPASS) {
                        
                        mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                        containerTimer.reset();
                        break;
                    }
                }
            }

            if (InvUtils.find(Items.COMPASS).isHotbar() && queueTimer.passedS(joinQueueDelay.get())) {
                InvUtils.swap(InvUtils.find(Items.COMPASS).slot(), false);
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                queueTimer.reset();
            }
        }
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    private void resetState() {
        login = false;
        queueTimer.reset();
        timer.reset();
        containerTimer.reset();
    }

    private class StaticListener {
        @EventHandler
        private void onGameJoined(ServerConnectBeginEvent event) {
            if (INSTANCE == null || !INSTANCE.isActive()) return;

            login = true;
            timer.reset();
            containerTimer.reset();
        }
    }
}
