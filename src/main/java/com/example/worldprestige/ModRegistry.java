package com.example.worldprestige;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** ブロック・アイテム・ブロックエンティティ・クリエイティブタブの登録。新しい物を足すときはここ。 */
public final class ModRegistry {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, WorldPrestige.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, WorldPrestige.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, WorldPrestige.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, WorldPrestige.MOD_ID);

    /** 電力を入れると World Fragment を作るブロック。 */
    public static final RegistryObject<Block> FRAGMENT_GENERATOR = BLOCKS.register("fragment_generator",
            () -> new FragmentGeneratorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL).strength(4.0f, 8.0f).requiresCorrectToolForDrops().sound(SoundType.METAL)));
    public static final RegistryObject<Item> FRAGMENT_GENERATOR_ITEM = ITEMS.register("fragment_generator",
            () -> new BlockItem(FRAGMENT_GENERATOR.get(), new Item.Properties()));

    /** 使うと Prestige Point が増えるアイテム。 */
    public static final RegistryObject<Item> WORLD_FRAGMENT = ITEMS.register("world_fragment",
            () -> new WorldFragmentItem(new Item.Properties().rarity(Rarity.RARE)));

    public static final RegistryObject<BlockEntityType<FragmentGeneratorBlockEntity>> FRAGMENT_GENERATOR_BE =
            BLOCK_ENTITIES.register("fragment_generator", () -> BlockEntityType.Builder
                    .of(FragmentGeneratorBlockEntity::new, FRAGMENT_GENERATOR.get()).build(null));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + WorldPrestige.MOD_ID))
                    .icon(() -> new ItemStack(WORLD_FRAGMENT.get()))
                    .displayItems((params, output) -> {
                        output.accept(WORLD_FRAGMENT.get());
                        output.accept(FRAGMENT_GENERATOR_ITEM.get());
                    })
                    .build());

    private ModRegistry() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        TABS.register(bus);
    }
}
