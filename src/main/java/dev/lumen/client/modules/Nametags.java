package dev.lumen.client.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import dev.lumen.client.Lumen;
import dev.lumen.client.gui.Ui;
import dev.lumen.client.hud.HudOverlay;
import dev.lumen.client.module.Category;
import dev.lumen.client.module.Module;
import dev.lumen.client.render.Projection;
import dev.lumen.client.setting.BoolSetting;
import dev.lumen.client.setting.NumberSetting;
import dev.lumen.client.util.ColorUtil;

/** Clean player labels with health, distance, ping and gear, readable through walls. */
public final class Nametags extends Module implements HudOverlay {
	private final NumberSetting scale = add(new NumberSetting("Scale", "Size of the labels.", 1.0, 0.5, 2.0, 0.05, "x"));
	private final NumberSetting range = add(new NumberSetting("Range", "Maximum distance, in blocks.", 128, 16, 512, 8, "m"));
	private final BoolSetting health = add(new BoolSetting("Health", "Show health, including absorption.", true));
	private final BoolSetting distance = add(new BoolSetting("Distance", "Show distance in blocks.", true));
	private final BoolSetting ping = add(new BoolSetting("Ping", "Show connection latency.", true));
	private final BoolSetting gear = add(new BoolSetting("Gear", "Show armor and the held item above the label.", true));
	private final BoolSetting mobs = add(new BoolSetting("Named mobs", "Also label mobs that have a name tag.", false));
	private final NumberSetting background = add(new NumberSetting("Background", "Opacity of the label backdrop.", 55, 0, 100, 1, "%"));

	public Nametags() {
		super("Nametags", "Player labels with health, distance, ping and gear, visible through walls.", Category.RENDER);
	}

	private record Tag(Entity entity, Projection.Point point) {
	}

	@Override
	public void drawOverlay(Ui ui, int screenWidth, int screenHeight) {
		if (MC.level == null || MC.player == null) return;
		float partialTick = MC.getDeltaTracker().getGameTimeDeltaPartialTick(true);

		List<Tag> tags = new ArrayList<>();
		for (Entity entity : MC.level.entitiesForRendering()) {
			if (entity == MC.player || !(entity instanceof LivingEntity)) continue;
			boolean isPlayer = entity instanceof Player;
			if (!isPlayer && !(mobs.isOn() && entity.hasCustomName())) continue;

			Vec3 pos = new Vec3(
					entity.xOld + (entity.getX() - entity.xOld) * partialTick,
					entity.yOld + (entity.getY() - entity.yOld) * partialTick + entity.getBbHeight() + 0.75,
					entity.zOld + (entity.getZ() - entity.zOld) * partialTick);
			Projection.Point point = Projection.toScreen(pos);
			if (point == null || point.distance() > range.get()) continue;
			tags.add(new Tag(entity, point));
		}

		// Far labels first, so near ones draw on top.
		tags.sort((a, b) -> Double.compare(b.point().distance(), a.point().distance()));
		for (Tag tag : tags) draw(ui, tag);
	}

	private void draw(Ui ui, Tag tag) {
		LivingEntity entity = (LivingEntity) tag.entity();
		Projection.Point point = tag.point();
		// Shrink gently with distance, but never below 60% so far tags stay readable.
		float s = scale.getFloat() * (float) Math.max(0.6, Math.min(1.0, 18.0 / Math.max(1.0, point.distance())));

		List<String> parts = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		parts.add(entity.getName().getString());
		colors.add(0xFFFFFFFF);

		if (health.isOn()) {
			float hp = entity.getHealth() + entity.getAbsorptionAmount();
			float fraction = Math.min(1f, entity.getHealth() / Math.max(1f, entity.getMaxHealth()));
			parts.add(String.format(Locale.ROOT, "%.0f", hp));
			colors.add(entity.getAbsorptionAmount() > 0 ? 0xFFFFD447 : ColorUtil.lerp(0xFFFF5252, 0xFF6CF0A0, fraction));
		}
		if (distance.isOn()) {
			parts.add(Math.round(point.distance()) + "m");
			colors.add(0xFFB7BCCF);
		}
		if (ping.isOn() && entity instanceof Player player && MC.getConnection() != null) {
			PlayerInfo info = MC.getConnection().getPlayerInfo(player.getUUID());
			if (info != null) {
				int ms = info.getLatency();
				parts.add(ms + "ms");
				colors.add(ms < 80 ? 0xFF6CF0A0 : ms < 200 ? 0xFFFFD447 : 0xFFFF6B7A);
			}
		}

		int gap = 5;
		int width = 0;
		for (String part : parts) width += ui.width(part);
		width += gap * (parts.size() - 1);

		var pose = ui.g.pose();
		pose.pushMatrix();
		pose.translate(point.x(), point.y());
		pose.scale(s, s);

		int x1 = -width / 2 - 4;
		int x2 = width / 2 + 4;
		int bg = Math.round(background.getFloat() / 100f * 255);
		ui.roundRect(x1, -11, x2, 1, 3, ColorUtil.argb(bg, 8, 9, 14));
		ui.rect(x1 + 3, 0, x2 - 3, 1, ColorUtil.fade(Lumen.modules().clickGui.accentAt(0.5f), 0.8f));

		int x = -width / 2;
		for (int i = 0; i < parts.size(); i++) {
			ui.text(parts.get(i), x, -9, colors.get(i));
			x += ui.width(parts.get(i)) + gap;
		}

		if (gear.isOn()) {
			List<ItemStack> stacks = new ArrayList<>();
			stacks.add(entity.getMainHandItem());
			stacks.add(entity.getItemBySlot(EquipmentSlot.HEAD));
			stacks.add(entity.getItemBySlot(EquipmentSlot.CHEST));
			stacks.add(entity.getItemBySlot(EquipmentSlot.LEGS));
			stacks.add(entity.getItemBySlot(EquipmentSlot.FEET));
			stacks.add(entity.getOffhandItem());
			stacks.removeIf(ItemStack::isEmpty);
			int ix = -stacks.size() * 9;
			for (ItemStack stack : stacks) {
				ui.g.item(stack, ix, -30);
				ix += 18;
			}
		}

		pose.popMatrix();
	}
}
