package dev.lumen.gametest;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * A stand-in for DonutSMP's /shop and /ah, laid out the way they are described: a
 * category menu, item pages with the price in lore, a buy screen with add, remove and
 * set buttons, and an auction house whose listings open a confirm screen. Text is in
 * small caps, as DonutSMP writes it. Like a server plugin, any click type on any slot is
 * a button press and nothing can be taken out. The real server could not be reached
 * from the test, so this is what the Printer's buying is tested against.
 */
public final class MockDonutServer implements ModInitializer {
	public static final double START_BALANCE = 10_000;
	private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

	private record Offer(Item item, double price) {
	}

	private record Listing(Item item, int count, double price, String priceText) {
	}

	private static final String[] CATEGORY_NAMES = {"End", "Nether", "Gear", "Food"};
	private static final Item[] CATEGORY_ICONS = {Items.END_STONE, Items.NETHERRACK, Items.TOTEM_OF_UNDYING, Items.COOKED_BEEF};
	private static final int[] CATEGORY_SLOTS = {10, 12, 14, 16};
	private static final List<List<Offer>> CATEGORIES = List.of(
			List.of(new Offer(Items.END_STONE, 5), new Offer(Items.ENDER_PEARL, 50), new Offer(Items.ENDER_CHEST, 250)),
			List.of(new Offer(Items.BLAZE_ROD, 40), new Offer(Items.NETHER_WART, 5), new Offer(Items.QUARTZ, 10)),
			List.of(new Offer(Items.OBSIDIAN, 60), new Offer(Items.END_CRYSTAL, 200), new Offer(Items.RESPAWN_ANCHOR, 300),
					new Offer(Items.TOTEM_OF_UNDYING, 5000)),
			List.of(new Offer(Items.COOKED_BEEF, 5), new Offer(Items.GOLDEN_CARROT, 20)));

	private static final Map<UUID, Double> BALANCE = new HashMap<>();
	private static final List<Listing> LISTINGS = new ArrayList<>();
	private static final List<String> PURCHASES = new ArrayList<>();
	private static final List<Runnable> LATER = new ArrayList<>();
	private static int clicks;

	@Override
	public void onInitialize() {
		reset();
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, selection) -> {
			dispatcher.register(Commands.literal("shop").executes(ctx -> {
				ServerPlayer player = ctx.getSource().getPlayerOrException();
				LATER.add(() -> openShop(player));
				return 1;
			}));
			dispatcher.register(Commands.literal("ah")
					.executes(ctx -> {
						ServerPlayer player = ctx.getSource().getPlayerOrException();
						LATER.add(() -> openAuction(player, ""));
						return 1;
					})
					.then(Commands.argument("query", StringArgumentType.greedyString()).executes(ctx -> {
						ServerPlayer player = ctx.getSource().getPlayerOrException();
						String query = StringArgumentType.getString(ctx, "query");
						LATER.add(() -> openAuction(player, query));
						return 1;
					})));
		});
		// Plugins act on clicks after the click packet is handled; so does this.
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			List<Runnable> run = new ArrayList<>(LATER);
			LATER.clear();
			run.forEach(Runnable::run);
		});
	}

	/** Fresh balances and listings; call on the server thread. */
	public static void reset() {
		BALANCE.clear();
		PURCHASES.clear();
		clicks = 0;
		LISTINGS.clear();
		// Out of order on purpose; the cheapest way to cover the need has to be worked out.
		LISTINGS.add(new Listing(Items.GLASS_PANE, 16, 16, "$16"));
		LISTINGS.add(new Listing(Items.GLASS, 8, 8000, "$8,000"));
		LISTINGS.add(new Listing(Items.GLASS, 64, 3200, "$3.2K"));
		LISTINGS.add(new Listing(Items.GLASS, 8, 480, "$480"));
		LISTINGS.add(new Listing(Items.GLASS, 4, 40, "$40"));
		LISTINGS.add(new Listing(Items.OAK_LOG, 64, 320_000, "$320K"));
	}

	public static double balance(UUID player) {
		return BALANCE.getOrDefault(player, START_BALANCE);
	}

	public static List<String> purchases() {
		return new ArrayList<>(PURCHASES);
	}

	/** Every click any shop or auction menu received. */
	public static int clicks() {
		return clicks;
	}

	// ---- shop ----

	private static void openShop(ServerPlayer player) {
		SimpleContainer items = filled(27);
		for (int i = 0; i < CATEGORY_SLOTS.length; i++) {
			items.setItem(CATEGORY_SLOTS[i], named(CATEGORY_ICONS[i], 1, small(CATEGORY_NAMES[i]), small("Click to browse")));
		}
		open(player, small("Shop"), 3, items, slot -> {
			for (int i = 0; i < CATEGORY_SLOTS.length; i++) {
				if (CATEGORY_SLOTS[i] == slot) openCategory(player, i);
			}
		});
	}

	private static void openCategory(ServerPlayer player, int category) {
		SimpleContainer items = filled(27);
		List<Offer> offers = CATEGORIES.get(category);
		for (int i = 0; i < offers.size(); i++) {
			Offer o = offers.get(i);
			items.setItem(10 + i, lore(new ItemStack(o.item()), small("Price: ") + money(o.price()), "", small("Click to buy")));
		}
		items.setItem(22, named(Items.ARROW, 1, small("Back")));
		open(player, small(CATEGORY_NAMES[category] + " shop"), 3, items, slot -> {
			if (slot == 22) openShop(player);
			else if (slot >= 10 && slot < 10 + offers.size()) openAmount(player, offers.get(slot - 10), 1);
		});
	}

	private static void openAmount(ServerPlayer player, Offer offer, int amount) {
		SimpleContainer items = filled(54);
		items.setItem(22, lore(new ItemStack(offer.item(), amount),
				small("Price: ") + money(offer.price()) + small(" each"), small("Total: ") + money(offer.price() * amount)));
		items.setItem(18, named(Items.RED_STAINED_GLASS_PANE, 1, small("Set to 1")));
		items.setItem(19, named(Items.RED_STAINED_GLASS_PANE, 1, small("Remove 10")));
		items.setItem(20, named(Items.RED_STAINED_GLASS_PANE, 1, small("Remove 1")));
		items.setItem(24, named(Items.LIME_STAINED_GLASS_PANE, 1, small("Add 1")));
		items.setItem(25, named(Items.LIME_STAINED_GLASS_PANE, 1, small("Add 10")));
		items.setItem(26, named(Items.LIME_STAINED_GLASS_PANE, 1, small("Set to 64")));
		items.setItem(39, named(Items.LIME_STAINED_GLASS_PANE, 1, small("Confirm")));
		items.setItem(41, named(Items.RED_STAINED_GLASS_PANE, 1, small("Cancel")));
		open(player, small("Buying ") + name(offer.item()), 6, items, slot -> {
			switch (slot) {
				case 18 -> openAmount(player, offer, 1);
				case 19 -> openAmount(player, offer, Math.max(1, amount - 10));
				case 20 -> openAmount(player, offer, Math.max(1, amount - 1));
				case 24 -> openAmount(player, offer, Math.min(64, amount + 1));
				case 25 -> openAmount(player, offer, Math.min(64, amount + 10));
				case 26 -> openAmount(player, offer, 64);
				case 39 -> {
					double total = offer.price() * amount;
					if (pay(player, total)) {
						player.getInventory().add(new ItemStack(offer.item(), amount));
						PURCHASES.add("shop " + amount + " " + id(offer.item()) + " " + money(total));
					}
					player.closeContainer();
				}
				case 41 -> player.closeContainer();
				default -> {
				}
			}
		});
	}

	// ---- auction house ----

	private static void openAuction(ServerPlayer player, String query) {
		String want = query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		SimpleContainer items = filled(54);
		for (int i = 0; i < 45; i++) items.setItem(i, ItemStack.EMPTY);
		List<Listing> shown = new ArrayList<>();
		for (Listing l : LISTINGS) {
			if (shown.size() < 45 && id(l.item()).contains(want)) shown.add(l);
		}
		for (int i = 0; i < shown.size(); i++) items.setItem(i, listingStack(shown.get(i)));
		items.setItem(45, named(Items.ARROW, 1, small("Previous page")));
		items.setItem(48, named(Items.OAK_SIGN, 1, small("Search")));
		items.setItem(49, named(Items.HOPPER, 1, small("Sort: Recently listed")));
		items.setItem(53, named(Items.ARROW, 1, small("Next page")));
		open(player, small("Auction house"), 6, items, slot -> {
			if (slot < shown.size()) openConfirm(player, shown.get(slot), query);
		});
	}

	private static void openConfirm(ServerPlayer player, Listing listing, String query) {
		SimpleContainer items = filled(27);
		items.setItem(13, listingStack(listing));
		items.setItem(11, named(Items.LIME_STAINED_GLASS_PANE, 1, small("Confirm")));
		items.setItem(15, named(Items.RED_STAINED_GLASS_PANE, 1, small("Cancel")));
		open(player, small("Confirm purchase"), 3, items, slot -> {
			if (slot == 11) {
				if (LISTINGS.contains(listing) && pay(player, listing.price())) {
					LISTINGS.remove(listing);
					player.getInventory().add(new ItemStack(listing.item(), listing.count()));
					PURCHASES.add("ah " + listing.count() + " " + id(listing.item()) + " " + money(listing.price()));
				}
				player.closeContainer();
			} else if (slot == 15) {
				openAuction(player, query);
			}
		});
	}

	private static ItemStack listingStack(Listing l) {
		return lore(new ItemStack(l.item(), l.count()), "", small("Price: ") + l.priceText(), small("Seller: ") + "Steve", "",
				small("Click to buy"));
	}

	// ---- helpers ----

	private static boolean pay(ServerPlayer player, double amount) {
		double have = balance(player.getUUID());
		if (have < amount) {
			player.sendSystemMessage(Component.literal(small("You don't have enough money")));
			return false;
		}
		BALANCE.put(player.getUUID(), have - amount);
		return true;
	}

	private static void open(ServerPlayer player, String title, int rows, SimpleContainer items, IntConsumer onClick) {
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new Gui(id, p, items, rows, onClick), Component.literal(title)));
	}

	private static final class Gui extends ChestMenu {
		private final IntConsumer onClick;
		private final int size;

		Gui(int id, Player player, SimpleContainer items, int rows, IntConsumer onClick) {
			super(rows == 6 ? MenuType.GENERIC_9x6 : MenuType.GENERIC_9x3, id, player.getInventory(), items, rows);
			this.onClick = onClick;
			this.size = rows * 9;
		}

		@Override
		public void clicked(int slot, int button, ContainerInput input, Player player) {
			// A plugin menu: every click is a button press, and nothing moves.
			if (slot < 0 || slot >= size) return;
			clicks++;
			LATER.add(() -> {
				if (player.containerMenu == this) onClick.accept(slot);
			});
		}

		@Override
		public ItemStack quickMoveStack(Player player, int slot) {
			return ItemStack.EMPTY;
		}
	}

	private static SimpleContainer filled(int size) {
		SimpleContainer items = new SimpleContainer(size);
		for (int i = 0; i < size; i++) items.setItem(i, named(Items.GRAY_STAINED_GLASS_PANE, 1, " "));
		return items;
	}

	private static ItemStack named(Item item, int count, String name, String... lore) {
		ItemStack stack = new ItemStack(item, count);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		return lore.length == 0 ? stack : lore(stack, lore);
	}

	private static ItemStack lore(ItemStack stack, String... lines) {
		List<Component> list = new ArrayList<>();
		for (String line : lines) list.add(Component.literal(line));
		stack.set(DataComponents.LORE, new ItemLore(list));
		return stack;
	}

	private static String small(String text) {
		StringBuilder sb = new StringBuilder();
		for (char c : text.toCharArray()) {
			char lower = Character.toLowerCase(c);
			sb.append(lower >= 'a' && lower <= 'z' ? SMALL_CAPS.charAt(lower - 'a') : c);
		}
		return sb.toString();
	}

	private static String money(double amount) {
		return "$" + new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(amount);
	}

	private static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath();
	}

	private static String name(Item item) {
		return new ItemStack(item).getHoverName().getString();
	}
}
