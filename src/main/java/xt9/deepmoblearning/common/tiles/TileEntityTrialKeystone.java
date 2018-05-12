package xt9.deepmoblearning.common.tiles;

import java.util.*;

import com.google.common.base.Predicates;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.items.CapabilityItemHandler;
import xt9.deepmoblearning.DeepConstants;
import xt9.deepmoblearning.DeepMobLearning;
import xt9.deepmoblearning.common.blocks.BlockTrialKeystone;
import xt9.deepmoblearning.common.capabilities.PlayerTrial;
import xt9.deepmoblearning.common.config.Config;
import xt9.deepmoblearning.common.entity.EntityGlitch;
import xt9.deepmoblearning.common.handlers.BaseItemHandler;
import xt9.deepmoblearning.common.handlers.TrialKeyHandler;
import xt9.deepmoblearning.common.items.ItemTrialKey;
import xt9.deepmoblearning.common.network.RequestKeystoneItemMessage;
import xt9.deepmoblearning.common.network.UpdateKeystoneItemMessage;
import xt9.deepmoblearning.common.trials.Trial;
import xt9.deepmoblearning.common.trials.TrialFactory;
import xt9.deepmoblearning.common.trials.TrialRuleset;
import xt9.deepmoblearning.common.trials.affix.*;
import xt9.deepmoblearning.common.util.PlayerHelper;
import xt9.deepmoblearning.common.util.Tier;
import xt9.deepmoblearning.common.util.TrialKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Created by xt9 on 2018-03-25.
 */
public class TileEntityTrialKeystone extends TileEntity implements ITickable, IGuiTile {
    public static final String NBT_LONG_TILE_POS = DeepConstants.MODID + ":tilepos";

    private boolean active = false;
    private int ticksToNextWave = 0;
    private int waveMobTotal = 0;
    private int mobsSpawned = 0;
    private int mobsDefeated = 0;
    private int currentWave = 0;
    private int lastWave = 0;
    private long tickCount = 0;

    private Trial trialData;
    private ItemStack activeKey = ItemStack.EMPTY;
    private NonNullList<ITrialAffix> affixes = NonNullList.create();
    private ThreadLocalRandom rand = ThreadLocalRandom.current();
    private final Set<EntityPlayerMP> participants = Collections.newSetFromMap(new WeakHashMap<>());
    public BaseItemHandler trialKey = new TrialKeyHandler() {
        @Override
        protected void onContentsChanged(int slot) {
            if (!world.isRemote) {
                DeepMobLearning.network.sendToAllAround(new UpdateKeystoneItemMessage(TileEntityTrialKeystone.this), new NetworkRegistry.TargetPoint(world.provider.getDimension(), pos.getX(), pos.getY(), pos.getZ(), 64));
            }
            super.onContentsChanged(slot);
        }
    };


    public void update() {
        tickCount++;

        if(!world.isRemote) {
            if(isTrialActive()) {
                disableFlying();

                if(participants.isEmpty()) {
                    finishTrial(true, true);
                } else if(ticksToNextWave > 0) {
                    ticksToNextWave--;
                    if(ticksToNextWave == 0) {
                        nextWave();
                    }
                    // Return early during wave intermission
                    return;
                } else if (currentWave <= lastWave) {
                    if(mobsSpawned < waveMobTotal) {
                        if(tickCount % (DeepConstants.TICKS_TO_SECOND * trialData.getSpawnDelay()) == 0) {
                            spawnTrialMob();
                        }
                    } else if(mobsDefeated == waveMobTotal) {
                        if(currentWave == (lastWave - 1)) {
                            finishTrial(false, true);
                        } else {
                            sendWaveCountdown(100);
                        }
                    }
                } else {
                    resetTrial();
                }

                runAffixes();

                // Somewhere around once every 14 seconds on launch
                if(tickCount % (DeepConstants.TICKS_TO_SECOND * 14) == 0) {
                    spawnGlitch();
                }

                // Update state every 15 sec, this is done more frequently when the container is open
                if(tickCount % (DeepConstants.TICKS_TO_SECOND * 15) ==  0) {
                    updateState();
                }
            }

            if(tickCount % (DeepConstants.TICKS_TO_SECOND * 5) ==  0) {
                markDirty();
            }
        }
    }

    private void disableFlying() {
        participants.forEach(p -> {
            if(!p.capabilities.isCreativeMode) {
                p.capabilities.allowFlying = false;
                p.capabilities.isFlying = false;
                p.sendPlayerAbilities();
            }
        });

    }

    private void runAffixes() {
        affixes.forEach(ITrialAffix::run);
    }


    public void startTrial() {
        if(hasTrialKey()) {
            activeKey = getTrialKey();
        } else {
            return;
        }

        if(!areaIsClear()) {
            return;
        }

        if (!TrialKey.isAttuned(activeKey)) {
            return;
        }

        BlockPos point1 = new BlockPos(pos.getX() - 7, pos.getY(), pos.getZ() - 7);
        BlockPos point2 = new BlockPos(pos.getX() + 7, pos.getY() + 3, pos.getZ() + 7);
        List<EntityPlayerMP> playersInArea = world.getEntitiesWithinAABB(EntityPlayerMP.class, new AxisAlignedBB(point1, point2), Predicates.alwaysTrue());
        if(playersInArea.isEmpty()) {
            return;
        } else {
            participants.addAll(playersInArea);
        }

        trialData = TrialFactory.createTrial(TrialKey.getMobKey(activeKey));
        lastWave = TrialRuleset.getMaxWaveFromTier(TrialKey.getTier(activeKey));
        waveMobTotal = trialData.getMobCountForWave(currentWave);
        affixes = TrialKey.getAffixes(activeKey, pos, world);
        trialKey.setStackInSlot(0, ItemStack.EMPTY);
        active = true;


        updateCapability();
        updateState();

        participants.forEach(p -> PlayerHelper.sendMessageToOverlay(p, "WaveNumber"));
    }

    private void sendWaveCountdown(int ticks) {
        ticksToNextWave = ticks;
        participants.forEach(participant -> PlayerHelper.sendMessageToOverlay(participant, "WaveCountdown"));
    }

    private void nextWave() {
        currentWave++;
        mobsDefeated = 0;
        mobsSpawned = 0;
        waveMobTotal = trialData.getMobCountForWave(currentWave);
        updateCapability();

        participants.forEach(participant -> PlayerHelper.sendMessageToOverlay(participant, "WaveNumber"));
    }

    public void updateCapability() {
        participants.forEach(p -> {
            PlayerTrial cap = (PlayerTrial) PlayerHelper.getTrialCapability(p);
            cap.setWaveMobTotal(waveMobTotal);
            cap.setCurrentWave(currentWave);
            cap.setDefeated(mobsDefeated);
            cap.setLastWave(lastWave);
            cap.setTilePos(pos.toLong());
            cap.sync(p);
        });
    }

    public void catchMobDeath() {
        mobsDefeated++;
        updateCapability();
    }

    public void playerDied() {
        if(isTrialActive()) {
            boolean isParticipantsDead = true;
            for (EntityPlayerMP participant : participants) {
                if(!participant.isDead) {
                    isParticipantsDead = false;
                }
            }

            if(isParticipantsDead) {
                participants.forEach(p -> PlayerHelper.sendMessage(p, new TextComponentString("All participants died. Better luck next time.")));
                finishTrial(true, false);
            }
        }
    }

    public void finishTrial(boolean abort, boolean sendMessages) {
        if(!abort) {
            if(sendMessages) {
                participants.forEach(p -> PlayerHelper.sendMessageToOverlay(p, "TrialCompleted"));
            }

            NonNullList<ItemStack> rewards = TrialFactory.getRewards(activeKey);
            rewards.forEach(stack -> {
                EntityItem item = new EntityItem(world, pos.getX(), pos.getY() + 2, pos.getZ(), stack);
                item.setDefaultPickupDelay();
                world.spawnEntity(item);
            });
        } else if(isTrialActive()) {
            // If the trial is active and for some reason aborted, Send a message to the user.
            if(sendMessages) {
                participants.forEach(p -> PlayerHelper.sendMessageToOverlay(p, "TrialAborted"));
            }
        }
        resetTrial();
    }

    public void resetTrial() {
        active = false;
        waveMobTotal = 0;
        mobsSpawned = 0;
        mobsDefeated = 0;
        lastWave = 0;
        currentWave = 0;
        updateCapability();
    }

    private void spawnTrialMob() {
        EntityLiving e = trialData.getTrialPrimaryEntity(world);

        // Spawn randomly within the confines of the trial
        int randomX = pos.getX() + rand.nextInt(-7, 7);
        int randomY = pos.getY() + rand.nextInt(0, 1);
        int randomZ = pos.getZ() + rand.nextInt(-7, 7);

        e.setLocationAndAngles(randomX, randomY, randomZ, 0 ,0);
        e.getEntityData().setLong(NBT_LONG_TILE_POS, getPos().toLong());
        e.enablePersistence();

        affixes.forEach(affix -> affix.apply(e));

        // Do not spawn them all at once (once every 2 sec atm)
        world.spawnEntity(e);
        mobsSpawned++;
    }

    private void spawnGlitch() {
        // Spawn randomly within the confines of the trial
        int randomX = pos.getX() + rand.nextInt(-7, 7);
        int randomY = pos.getY() + rand.nextInt(0, 1);
        int randomZ = pos.getZ() + rand.nextInt(-7, 7);

        if(rand.nextInt(1, 100) <= TrialRuleset.getGlitchSpawnChance(TrialKey.getTier(activeKey))) {
            EntityGlitch e = TrialRuleset.getGlitch(world);
            e.setLocationAndAngles(randomX, randomY, randomZ, 0, 0);
            e.enablePersistence();

            affixes.forEach(affix -> affix.applyToGlitch(e));

            world.spawnEntity(e);

            participants.forEach(p -> PlayerHelper.sendMessageToOverlay(p, "GlitchNotification"));
        }
    }

    public boolean areaIsClear() {
        Iterable<BlockPos> groundLayer;
        Iterable<BlockPos> airLayer;

        // Get all blocks from the layer under the trial keystone
        groundLayer = BlockPos.getAllInBox(
            new BlockPos(pos.getX() - 7, pos.getY() - 1, pos.getZ() - 7),
            new BlockPos(pos.getX() + 7, pos.getY() - 1, pos.getZ() + 7)
        );

        for (BlockPos blockPos : groundLayer) {
            if(!world.getBlockState(blockPos).isFullBlock()) {
                return false;
            }
        }

        airLayer = BlockPos.getAllInBox(
            new BlockPos(pos.getX() - 7, pos.getY(), pos.getZ() - 7),
            new BlockPos(pos.getX() + 7, pos.getY() + 10, pos.getZ() + 7)
        );

        for (BlockPos blockPos : airLayer) {
            IBlockState state = world.getBlockState(blockPos);
            Block block = state.getBlock();

            if(!block.isAir(state, world, blockPos) && !(block instanceof BlockTrialKeystone)) {
                return false;
            }
        }

        return true;
    }

    public boolean isTrialActive() {
        return active;
    }

    public ItemStack getTrialKey() {
        return trialKey.getStackInSlot(0);
    }

    public int getCurrentWave() {
        return currentWave;
    }

    public int getLastWave() {
        return lastWave;
    }

    public boolean hasTrialKey() {
        return getTrialKey().getItem() instanceof ItemTrialKey;
    }

    public void updateState() {
        IBlockState state = world.getBlockState(getPos());
        world.notifyBlockUpdate(getPos(), state, state, 3);
    }

    @Override
    public void onLoad() {
        if (world.isRemote) {
            DeepMobLearning.network.sendToServer(new RequestKeystoneItemMessage(this));
        }
        super.onLoad();
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(getPos(), getPos().add(1, 2, 1));
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return getNetworkTag(super.getUpdateTag());
    }

    private NBTTagCompound getNetworkTag(NBTTagCompound tag) {
        tag.setTag("inventory", trialKey.serializeNBT());
        tag.setBoolean("active", active);
        tag.setInteger("currentWave", currentWave);
        tag.setInteger("lastWave", lastWave);

        return tag;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        NBTTagCompound nbtTag = new NBTTagCompound();
        writeToNBT(nbtTag);
        return new SPacketUpdateTileEntity(getPos(), -1, nbtTag);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        // Here we get the packet from the server and read it into our client side tile particle
        super.onDataPacket(net, packet);
        readFromNBT(packet.getNbtCompound());
    }
    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        compound.setBoolean("active", active);
        compound.setInteger("currentWave", currentWave);
        compound.setInteger("lastWave", lastWave);
        compound.setInteger("mobsSpawned", mobsSpawned);
        compound.setInteger("mobsDefeated", mobsDefeated);
        compound.setInteger("waveMobTotal", waveMobTotal);
        compound.setTag("inventory", trialKey.serializeNBT());
        return super.writeToNBT(compound);
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        active = compound.getBoolean("active");
        currentWave = compound.getInteger("currentWave");
        lastWave = compound.getInteger("lastWave");
        mobsSpawned = compound.getInteger("mobsSpawned");
        mobsDefeated = compound.getInteger("mobsDefeated");
        waveMobTotal = compound.getInteger("waveMobTotal");
        trialKey.deserializeNBT(compound.getCompoundTag("inventory"));
        super.readFromNBT(compound);
    }

    @Override
    public boolean hasCapability(Capability capability, @Nullable EnumFacing facing) {
        return capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY || super.hasCapability(capability, facing);
    }

    @Nullable
    @Override
    public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
        if(capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
            // If facing is null its interacting with a player or some fake player
            if(facing == null) {
                return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(trialKey);
            }
        }

        return super.getCapability(capability, facing);
    }

    public int getGuiID() {
        return DeepConstants.TILE_TRIAL_KEYSTONE_GUI_ID;
    }
}
