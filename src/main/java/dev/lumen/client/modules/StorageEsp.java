package dev.lumen.client.modules;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;

import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.ColorSetting;
import dev.lumen.client.util.ColorUtil;

public final class StorageEsp extends EspModule {
	private final ColorSetting chests = add(new ColorSetting("Chests", "Regular and copper chests.", 0xFFFFB347, true));
	private final ColorSetting trappedChests = add(new ColorSetting("Trapped chests", "Chests that emit redstone when opened.", 0xFFFF5E5E, true));
	private final ColorSetting enderChests = add(new ColorSetting("Ender chests", "Personal ender storage.", 0xFFB86BFF, true));
	private final ColorSetting shulkers = add(new ColorSetting("Shulker boxes", "Shulker boxes of every colour.", 0xFFFF7AD9, true));
	private final BoolSetting shulkerDyeColors = add(new BoolSetting("Shulker dye colors", "Tint each shulker box with its own dye colour.", true))
			.visibleWhen(shulkers::isEnabled);
	private final ColorSetting droppers = add(new ColorSetting("Droppers", "Droppers.", 0xFF8BE36B, true));
	private final ColorSetting barrels = add(new ColorSetting("Barrels", "Barrels.", 0xFFD39A5B, true));
	private final ColorSetting dispensers = add(new ColorSetting("Dispensers", "Dispensers.", 0xFF5EC8FF, false));
	private final ColorSetting hoppers = add(new ColorSetting("Hoppers", "Hoppers.", 0xFF9AA5B1, false));
	private final ColorSetting furnaces = add(new ColorSetting("Furnaces", "Furnaces, smokers and blast furnaces.", 0xFFE6E6E6, false));
	private final ColorSetting crafters = add(new ColorSetting("Crafters", "Crafters.", 0xFFFFD95E, false));
	private final ColorSetting pots = add(new ColorSetting("Decorated pots", "Decorated pots, which hold one stack.", 0xFFC7714A, false));

	public StorageEsp() {
		super("Storage ESP", "Highlights chests, shulker boxes, droppers and other storage blocks.", 256, false);
	}

	@Override
	protected Target match(BlockEntity be, ClientLevel level) {
		// Order matters: trapped chests are chests, and droppers are dispensers.
		ColorSetting setting;
		if (be instanceof TrappedChestBlockEntity) setting = trappedChests;
		else if (be instanceof ChestBlockEntity) setting = chests;
		else if (be instanceof EnderChestBlockEntity) setting = enderChests;
		else if (be instanceof ShulkerBoxBlockEntity) setting = shulkers;
		else if (be instanceof DropperBlockEntity) setting = droppers;
		else if (be instanceof DispenserBlockEntity) setting = dispensers;
		else if (be instanceof BarrelBlockEntity) setting = barrels;
		else if (be instanceof HopperBlockEntity) setting = hoppers;
		else if (be instanceof AbstractFurnaceBlockEntity) setting = furnaces;
		else if (be instanceof CrafterBlockEntity) setting = crafters;
		else if (be instanceof DecoratedPotBlockEntity) setting = pots;
		else return null;

		if (!setting.isEnabled()) return null;

		BlockPos pos = be.getBlockPos();
		BlockState state = be.getBlockState();
		AABB box;

		if (be instanceof ChestBlockEntity) {
			box = chestBox(level, pos, state);
			if (box == null) return null;
		} else {
			box = blockBox(level, pos, state);
		}

		int fixed = 0;
		if (setting == shulkers && shulkerDyeColors.isOn() && !shulkers.isRainbow()) {
			int dye = shulkerDye(state);
			if (dye != 0) fixed = ColorUtil.withAlpha(dye, ColorUtil.alpha(shulkers.get()));
		}

		return new Target(box, setting, fixed);
	}

	/** One box for both halves of a double chest; the left half is skipped. */
	private static AABB chestBox(ClientLevel level, BlockPos pos, BlockState state) {
		AABB box = blockBox(level, pos, state);
		if (!state.hasProperty(ChestBlock.TYPE)) return box;

		ChestType type = state.getValue(ChestBlock.TYPE);
		if (type == ChestType.LEFT) return null;
		if (type == ChestType.SINGLE) return box;

		BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
		BlockState otherState = level.getBlockState(other);
		if (otherState.isAir()) return box;
		return box.minmax(blockBox(level, other, otherState));
	}

	/** The dye colour of a shulker box from its block id, or 0 for the undyed box. */
	private static int shulkerDye(BlockState state) {
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		if (!path.endsWith("_shulker_box")) return 0;
		String dye = path.substring(0, path.length() - "_shulker_box".length());
		int rgb = switch (dye) {
			case "white" -> 0xF9FFFE;
			case "orange" -> 0xF9801D;
			case "magenta" -> 0xC74EBD;
			case "light_blue" -> 0x3AB3DA;
			case "yellow" -> 0xFED83D;
			case "lime" -> 0x80C71F;
			case "pink" -> 0xF38BAA;
			case "gray" -> 0x6B7478;
			case "light_gray" -> 0x9D9D97;
			case "cyan" -> 0x169C9C;
			case "purple" -> 0x9A45CC;
			case "blue" -> 0x4F58D0;
			case "brown" -> 0xA06A40;
			case "green" -> 0x6E9120;
			case "red" -> 0xD0392F;
			case "black" -> 0x3A3A44;
			default -> -1;
		};
		return rgb == -1 ? 0 : 0xFF000000 | rgb;
	}
}
