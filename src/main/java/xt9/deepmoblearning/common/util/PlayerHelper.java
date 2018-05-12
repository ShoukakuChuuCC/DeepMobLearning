package xt9.deepmoblearning.common.util;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.common.FMLCommonHandler;
import xt9.deepmoblearning.DeepMobLearning;
import xt9.deepmoblearning.common.capabilities.IPlayerTrial;
import xt9.deepmoblearning.common.capabilities.PlayerTrial;
import xt9.deepmoblearning.common.capabilities.PlayerTrialProvider;
import xt9.deepmoblearning.common.items.ItemDeepLearner;
import xt9.deepmoblearning.common.network.UpdateTrialOverlayMessage;

import java.awt.*;
import java.util.UUID;

/**
 * Created by xt9 on 2017-06-14.
 */
public class PlayerHelper {
    @SuppressWarnings("ConstantConditions")
    public static IPlayerTrial getTrialCapability(EntityPlayerMP player) {
        if(PlayerTrialProvider.PLAYER_TRIAL_CAP != null) {
            return player.getCapability(PlayerTrialProvider.PLAYER_TRIAL_CAP, null);
        }
        /* This will never happen, but tricks the IDE that the object is not always null */
        return new PlayerTrial();
    }

    public static void sendMessageToOverlay(EntityPlayerMP player, String type) {
        if(player != null) {
            DeepMobLearning.network.sendTo(new UpdateTrialOverlayMessage(type), player);
        }
    }

    public static void sendMessage(EntityPlayerMP player, TextComponentString component) {
        if(player != null) {
            player.sendMessage(component);
        }
    }

    private boolean isHoldingDeepLearner;
    private EntityPlayer player;
    private ItemStack stack;

    public PlayerHelper(EntityPlayer player) {
        this.player = player;
        ItemStack mainHandStack = this.player.getHeldItem(EnumHand.MAIN_HAND);
        ItemStack offHandStack = this.player.getHeldItem(EnumHand.OFF_HAND);

        if(mainHandStack.getItem() instanceof ItemDeepLearner) {
            this.stack = mainHandStack;
            this.isHoldingDeepLearner = true;
        } else if(offHandStack.getItem() instanceof ItemDeepLearner) {
            this.stack = offHandStack;
            this.isHoldingDeepLearner = true;
        } else {
            this.isHoldingDeepLearner = false;
            stack = ItemStack.EMPTY;
        }

    }

    public static EntityPlayerMP getPlayerFromUUID(UUID uuid) {
        PlayerList list = FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList();
        return list.getPlayerByUUID(uuid);
    }


    public boolean isHoldingDeepLearner() {
        return isHoldingDeepLearner;
    }

    // Check if isHoldingDeepLearner first if you can't accept an Empty itemstack
    public ItemStack getHeldDeepLearner() {
        return stack;
    }
}
