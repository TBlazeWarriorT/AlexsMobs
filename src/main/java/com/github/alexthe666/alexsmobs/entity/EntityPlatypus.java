package com.github.alexthe666.alexsmobs.entity;

import com.github.alexthe666.alexsmobs.block.AMBlockRegistry;
import com.github.alexthe666.alexsmobs.block.BlockReptileEgg;
import com.github.alexthe666.alexsmobs.client.particle.AMParticleRegistry;
import com.github.alexthe666.alexsmobs.config.AMConfig;
import com.github.alexthe666.alexsmobs.entity.ai.*;
import com.github.alexthe666.alexsmobs.entity.util.Maths;
import com.github.alexthe666.alexsmobs.item.AMItemRegistry;
import com.github.alexthe666.alexsmobs.misc.AMSoundRegistry;
import com.github.alexthe666.alexsmobs.misc.AMTagRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Bucketable;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class EntityPlatypus extends Animal implements ISemiAquatic, ITargetsDroppedItems, Bucketable {

    private static final EntityDataAccessor<Boolean> SENSING = SynchedEntityData.defineId(EntityPlatypus.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> SENSING_VISUAL = SynchedEntityData.defineId(EntityPlatypus.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DIGGING = SynchedEntityData.defineId(EntityPlatypus.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> FEDORA = SynchedEntityData.defineId(EntityPlatypus.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> FROM_BUCKET = SynchedEntityData.defineId(EntityPlatypus.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> HAS_EGG = SynchedEntityData.defineId(EntityPlatypus.class, EntityDataSerializers.BOOLEAN);
    public float prevInWaterProgress;
    public float inWaterProgress;
    public float prevDigProgress;
    public float digProgress;
    public boolean superCharged = false;
    private boolean isLandNavigator;
    private int swimTimer = -1000;

    protected EntityPlatypus(EntityType type, Level world) {
        super(type, world);
        this.setPathfindingMalus(BlockPathTypes.WATER, 0.0F);
        this.setPathfindingMalus(BlockPathTypes.WATER_BORDER, 0.0F);
        switchNavigator(false);
    }

    public static boolean canPlatypusSpawn(EntityType type, LevelAccessor worldIn, MobSpawnType reason, BlockPos pos, RandomSource randomIn) {
        boolean spawnBlock = worldIn.getBlockState(pos.below()).is(AMTagRegistry.PLATYPUS_SPAWNS);
        return (worldIn.getBlockState(pos.below()).getBlock() == Blocks.DIRT || spawnBlock) && pos.getY() < worldIn.getSeaLevel() + 4;
    }

    public boolean checkSpawnRules(LevelAccessor worldIn, MobSpawnType spawnReasonIn) {
        return AMEntityRegistry.rollSpawn(AMConfig.platypusSpawnRolls, this.getRandom(), spawnReasonIn);
    }

    public static AttributeSupplier.Builder bakeAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 10.0D).add(Attributes.FOLLOW_RANGE, 16.0D).add(Attributes.MOVEMENT_SPEED, 0.2F);
    }

    public boolean isFood(ItemStack stack) {
        Item item = stack.getItem();
        return item == AMItemRegistry.LOBSTER_TAIL.get() || item == AMItemRegistry.COOKED_LOBSTER_TAIL.get();
    }


    protected SoundEvent getAmbientSound() {
        return AMSoundRegistry.PLATYPUS_IDLE.get();
    }

    protected SoundEvent getHurtSound(DamageSource damageSourceIn) {
        return AMSoundRegistry.PLATYPUS_HURT.get();
    }

    protected SoundEvent getDeathSound() {
        return AMSoundRegistry.PLATYPUS_HURT.get();
    }

    @Override
    @Nonnull
    public ItemStack getBucketItemStack() {
        ItemStack stack = new ItemStack(AMItemRegistry.PLATYPUS_BUCKET.get());
        if (this.hasCustomName()) {
            stack.setHoverName(this.getCustomName());
        }
        return stack;
    }

    @Override
    public void saveToBucketTag(@Nonnull ItemStack bucket) {
        if (this.hasCustomName()) {
            bucket.setHoverName(this.getCustomName());
        }
        CompoundTag platTag = new CompoundTag();
        this.addAdditionalSaveData(platTag);
        CompoundTag compound = bucket.getOrCreateTag();
        compound.put("PlatypusData", platTag);
    }

    @Override
    public void loadFromBucketTag(@Nonnull CompoundTag compound) {
        if (compound.contains("PlatypusData")) {
            this.readAdditionalSaveData(compound.getCompound("PlatypusData"));
        }
    }

    @Override
    @Nonnull
    public InteractionResult mobInteract(@Nonnull Player player, @Nonnull InteractionHand hand) {
        ItemStack itemstack = player.getItemInHand(hand);
        boolean redstone = itemstack.getItem() == Items.REDSTONE || itemstack.getItem() == Items.REDSTONE_BLOCK;
        if(itemstack.getItem() == AMItemRegistry.FEDORA.get() && !this.hasFedora()){
            if (!player.isCreative()) {
                itemstack.shrink(1);
            }
            this.setFedora(true);
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        if (redstone && !this.isSensing()) {
            superCharged = itemstack.getItem() == Items.REDSTONE_BLOCK;
            if (!player.isCreative()) {
                itemstack.shrink(1);
            }
            this.setSensing(true);
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        return Bucketable.bucketMobPickup(player, hand, this).orElse(super.mobInteract(player, hand));
    }

    protected void registerGoals() {
        this.goalSelector.addGoal(0, new BreathAirGoal(this));
        this.goalSelector.addGoal(1, new AnimalAIFindWater(this));
        this.goalSelector.addGoal(1, new AnimalAILeaveWater(this));
        this.goalSelector.addGoal(2, new MateGoal(this, 1.0D));
        this.goalSelector.addGoal(2, new LayEggGoal(this, 1.0D));
        this.goalSelector.addGoal(2, new BreedGoal(this, 0.8D));
        this.goalSelector.addGoal(3, new PanicGoal(this, 1.1D));
        this.goalSelector.addGoal(3, new TemptGoal(this, 1.0D, Ingredient.of(Items.REDSTONE, Items.REDSTONE_BLOCK), false){
            public void start() {
                super.start();
                EntityPlatypus.this.setSensingVisual(true);
            }

            public boolean canUse(){
                return super.canUse() && !EntityPlatypus.this.isSensing();
            }

            public void stop() {
                super.stop();
                EntityPlatypus.this.setSensingVisual(false);
            }
        });
        this.goalSelector.addGoal(5, new TemptGoal(this, 1.1D, Ingredient.of(AMTagRegistry.PLATYPUS_FOODSTUFFS), false){
            public boolean canUse(){
                return super.canUse() && !EntityPlatypus.this.isSensing();
            }
        });
        this.goalSelector.addGoal(5, new PlatypusAIDigForItems(this));
        this.goalSelector.addGoal(6, new SemiAquaticAIRandomSwimming(this, 1.0D, 30));
        this.goalSelector.addGoal(7, new RandomStrollGoal(this, 1.0D, 60));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 6.0F));
        this.targetSelector.addGoal(1, new CreatureAITargetItems(this, false, false, 40, 15){
            public boolean canUse(){
                return super.canUse() && !EntityPlatypus.this.isSensing();
            }

            public boolean canContinueToUse(){
                return super.canContinueToUse() && !EntityPlatypus.this.isSensing();
            }
        });
    }

    public boolean hurt(DamageSource source, float amount) {
        boolean prev = super.hurt(source, amount);
        if(prev && source.getDirectEntity() instanceof LivingEntity){
            LivingEntity entity = (LivingEntity)source.getDirectEntity();
            entity.addEffect(new MobEffectInstance(MobEffects.POISON, 100));
        }
        return prev;
    }

    public boolean isPerry() {
        String s = ChatFormatting.stripFormatting(this.getName().getString());
        return s != null && s.toLowerCase().contains("perry");
    }


    public int getMaxAirSupply() {
        return 4800;
    }

    protected int increaseAirSupply(int currentAir) {
        return this.getMaxAirSupply();
    }

    public void spawnGroundEffects() {
        float radius = 0.3F;
        for (int i1 = 0; i1 < 3; i1++) {
            double motionX = getRandom().nextGaussian() * 0.07D;
            double motionY = getRandom().nextGaussian() * 0.07D;
            double motionZ = getRandom().nextGaussian() * 0.07D;
            float angle = (Maths.STARTING_ANGLE * this.yBodyRot) + i1;
            double extraX = radius * Mth.sin(Mth.PI + angle);
            double extraY = 0.8F;
            double extraZ = radius * Mth.cos(angle);
            BlockPos ground = this.getBlockPosBelowThatAffectsMyMovement();
            BlockState state = this.level().getBlockState(ground);
            if (state.isSolid()) {
                if (this.level().isClientSide) {
                    level().addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), true, this.getX() + extraX, ground.getY() + extraY, this.getZ() + extraZ, motionX, motionY, motionZ);
                }
            }
        }
    }

    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor worldIn, DifficultyInstance difficultyIn, MobSpawnType
            reason, @Nullable SpawnGroupData spawnDataIn, @Nullable CompoundTag dataTag) {
        this.setAirSupply(this.getMaxAirSupply());
        return super.finalizeSpawn(worldIn, difficultyIn, reason, spawnDataIn, dataTag);
    }

    public boolean isPushedByFluid() {
        return false;
    }

    public void travel(Vec3 travelVector) {
        if (this.isEffectiveAi() && this.isInWater()) {
            this.moveRelative(this.getSpeed(), travelVector);
            this.move(MoverType.SELF, this.getDeltaMovement());
            this.setDeltaMovement(this.getDeltaMovement().scale(0.9D));
        } else {
            super.travel(travelVector);
        }
    }

    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DIGGING, false);
        this.entityData.define(SENSING, false);
        this.entityData.define(SENSING_VISUAL, false);
        this.entityData.define(FEDORA, false);
        this.entityData.define(FROM_BUCKET, false);
        this.entityData.define(HAS_EGG, false);
    }

    protected void dropEquipment() {
        super.dropEquipment();
        if (this.hasFedora()) {
            this.spawnAtLocation(AMItemRegistry.FEDORA.get());
        }

    }

    public boolean isSensing() {
        return this.entityData.get(SENSING);
    }

    public void setSensing(boolean sensing) {
        this.entityData.set(SENSING, Boolean.valueOf(sensing));
    }

    public boolean isSensingVisual() {
        return this.entityData.get(SENSING_VISUAL);
    }

    public void setSensingVisual(boolean sensing) {
        this.entityData.set(SENSING_VISUAL, Boolean.valueOf(sensing));
    }

    public boolean hasFedora() {
        return this.entityData.get(FEDORA);
    }

    public void setFedora(boolean sensing) {
        this.entityData.set(FEDORA, Boolean.valueOf(sensing));
    }

    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putBoolean("Fedora", this.hasFedora());
        compound.putBoolean("Sensing", this.isSensing());
        compound.putBoolean("FromBucket", this.fromBucket());
        compound.putBoolean("HasEgg", this.hasEgg());
    }

    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        this.setFedora(compound.getBoolean("Fedora"));
        this.setSensing(compound.getBoolean("Sensing"));
        this.setFromBucket(compound.getBoolean("FromBucket"));
        this.setHasEgg(compound.getBoolean("HasEgg"));
    }

    @Override
    public boolean fromBucket() {
        return this.entityData.get(FROM_BUCKET);
    }

    @Override
    public void setFromBucket(boolean p_203706_1_) {
        this.entityData.set(FROM_BUCKET, p_203706_1_);
    }

    @Override
    @Nonnull
    public SoundEvent getPickupSound() {
        return SoundEvents.BUCKET_FILL_FISH;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return super.requiresCustomPersistence() || this.fromBucket() || this.hasCustomName();
    }

    @Override
    public boolean removeWhenFarAway(double dist) {
        return !this.fromBucket() && !this.requiresCustomPersistence();
    }

    public void tick() {
        super.tick();
        prevInWaterProgress = inWaterProgress;
        prevDigProgress = digProgress;
        boolean dig = isDigging() && isInWaterOrBubble();
        if (dig && digProgress < 5F) {
            digProgress++;
        }
        if (!dig && digProgress > 0F) {
            digProgress--;
        }

        if (this.isInWaterOrBubble()) {
            if (inWaterProgress < 5F)
                inWaterProgress++;

            if (this.isLandNavigator)
                switchNavigator(false);
        } else {
            if (inWaterProgress > 0F)
                inWaterProgress--;

            if (!this.isLandNavigator)
                switchNavigator(true);
        }

        if (this.onGround() && isDigging()) {
            spawnGroundEffects();
        }
        if (inWaterProgress > 0) {
            this.setMaxUpStep(1);
        } else {
            this.setMaxUpStep(0.6F);
        }
        if (!this.level().isClientSide) {
            if (isInWater()) {
                swimTimer++;
            } else {
                swimTimer--;
            }
        }
        if (this.isAlive() && (this.isSensing() || this.isSensingVisual())) {
            for (int j = 0; j < 2; ++j) {
                float radius = this.getBbWidth() * 0.65F;
                float angle = (Maths.STARTING_ANGLE * this.yBodyRot);
                double extraX = (radius * (1.5F + random.nextFloat() * 0.3F)) * Mth.sin(Mth.PI + angle) + (random.nextFloat() - 0.5F) + this.getDeltaMovement().x * 2F;
                double extraZ = (radius * (1.5F + random.nextFloat() * 0.3F)) * Mth.cos(angle) + (random.nextFloat() - 0.5F) + this.getDeltaMovement().z * 2F;
                double actualX = radius * Mth.sin(Mth.PI + angle);
                double actualZ = radius * Mth.cos(angle);
                double motX = actualX - extraX;
                double motZ = actualZ - extraZ;
                this.level().addParticle(AMParticleRegistry.PLATYPUS_SENSE.get(), this.getX() + extraX, this.getBbHeight() * 0.3F + this.getY(), this.getZ() + extraZ, motX * 0.1F, 0, motZ * 0.1F);
            }
        }
    }

    public boolean isDigging() {
        return this.entityData.get(DIGGING);
    }

    public void setDigging(boolean digging) {
        this.entityData.set(DIGGING, digging);
    }

    private void switchNavigator(boolean onLand) {
        if (onLand) {
            this.moveControl = new MoveControl(this);
            this.navigation = new GroundPathNavigatorWide(this, level());
            this.isLandNavigator = true;
        } else {
            this.moveControl = new AnimalSwimMoveControllerSink(this, 1.2F, 1.6F);
            this.navigation = new SemiAquaticPathNavigator(this, level());
            this.isLandNavigator = false;
        }
    }

    @Override
    public boolean shouldEnterWater() {
        return (this.getLastHurtByMob() != null || swimTimer <= -1000 || this.isSensing()) && !this.hasEgg();
    }

    @Override
    public boolean shouldLeaveWater() {
        return swimTimer > 600 && !this.isSensing() || this.hasEgg();
    }

    @Override
    public boolean shouldStopMoving() {
        return this.isDigging();
    }

    @Override
    public int getWaterSearchRange() {
        return 10;
    }

    @Nullable
    @Override
    public AgeableMob getBreedOffspring(ServerLevel serverWorld, AgeableMob ageableEntity) {
        return AMEntityRegistry.PLATYPUS.get().create(serverWorld);
    }

    @Override
    public boolean canTargetItem(ItemStack stack) {
        return !this.isSensing() && stack.is(AMTagRegistry.PLATYPUS_FOODSTUFFS);
    }

    @Override
    public void onGetItem(ItemEntity e) {
        this.gameEvent(GameEvent.EAT);
        this.playSound(SoundEvents.CAT_EAT, this.getSoundVolume(), this.getVoicePitch());
        if(e.getItem().getItem() == Items.REDSTONE || e.getItem().getItem() == Items.REDSTONE_BLOCK){
            superCharged = e.getItem().getItem() == Items.REDSTONE_BLOCK;
            this.setSensing(true);
        }else{
            this.heal(6);
        }
    }

    public boolean hasEgg() {
        return this.entityData.get(HAS_EGG);
    }

    private void setHasEgg(boolean hasEgg) {
        this.entityData.set(HAS_EGG, hasEgg);
    }

    static class MateGoal extends BreedGoal {
        private final EntityPlatypus platypus;

        MateGoal(EntityPlatypus platypus, double speedIn) {
            super(platypus, speedIn);
            this.platypus = platypus;
        }

        public boolean canUse() {
            return super.canUse() && !this.platypus.hasEgg();
        }

        protected void breed() {
            ServerPlayer serverplayerentity = this.animal.getLoveCause();
            if (serverplayerentity == null && this.partner.getLoveCause() != null) {
                serverplayerentity = this.partner.getLoveCause();
            }

            if (serverplayerentity != null) {
                serverplayerentity.awardStat(Stats.ANIMALS_BRED);
                CriteriaTriggers.BRED_ANIMALS.trigger(serverplayerentity, this.animal, this.partner, this.animal);
            }

            this.platypus.setHasEgg(true);
            this.animal.resetLove();
            this.partner.resetLove();
            this.animal.setAge(6000);
            this.partner.setAge(6000);

            if (this.level.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT)) {
                final RandomSource random = this.animal.getRandom();
                this.level.addFreshEntity(new ExperienceOrb(this.level, this.animal.getX(), this.animal.getY(), this.animal.getZ(), random.nextInt(7) + 1));
            }

        }
    }

    static class LayEggGoal extends MoveToBlockGoal {
        private final EntityPlatypus turtle;
        private int digTime;

        LayEggGoal(EntityPlatypus turtle, double speedIn) {
            super(turtle, speedIn, 16);
            this.turtle = turtle;
        }

        public void stop() {
            digTime = 0;
        }

        public boolean canUse() {
            return this.turtle.hasEgg() && super.canUse();
        }

        public boolean canContinueToUse() {
            return super.canContinueToUse() && this.turtle.hasEgg();
        }

        public double acceptedDistance() {
            return turtle.getBbWidth() + 0.5D;
        }

        public void tick() {
            super.tick();
            if (!this.turtle.isInWater() && this.isReachedTarget()) {
                final BlockPos blockpos = this.turtle.blockPosition();
                final Level world = this.turtle.level();
                turtle.gameEvent(GameEvent.BLOCK_PLACE);
                world.playSound(null, blockpos, SoundEvents.TURTLE_LAY_EGG, SoundSource.BLOCKS, 0.3F, 0.9F + world.random.nextFloat() * 0.2F);
                world.setBlock(this.blockPos.above(), AMBlockRegistry.PLATYPUS_EGG.get().defaultBlockState().setValue(BlockReptileEgg.EGGS, Integer.valueOf(this.turtle.random.nextInt(1) + 1)), 3);
                this.turtle.setHasEgg(false);
                this.turtle.setDigging(false);
                this.turtle.setInLoveTime(600);
            }
        }

        protected boolean isValidTarget(LevelReader worldIn, BlockPos pos) {
            return worldIn.isEmptyBlock(pos.above()) && BlockReptileEgg.isProperHabitat(worldIn, pos);
        }
    }
}
