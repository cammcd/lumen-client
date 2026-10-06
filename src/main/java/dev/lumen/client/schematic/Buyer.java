package dev.lumen.client.schematic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Buys a block through a server's shop menus, the way a player would: run /shop, open
 * each category until the block turns up, set the amount, confirm; failing that, search
 * the auction house and buy the listing that covers the need for the least money.
 *
 * Nothing is assumed about where a server puts its buttons. Items are found by type,
 * buttons by what their names say (Add 10, Set to 64, Confirm, Cancel), prices from
 * lore. Every purchase is checked against the price limit and the spending cap before
 * it is confirmed, and only counts once the items are actually in the inventory.
 */
public final class Buyer {
	private static final Minecraft MC = Minecraft.getInstance();
	private static final int OPEN_TIMEOUT = 60;
	private static final int CLICK_TIMEOUT = 30;
	private static final int DELIVERY_TIMEOUT = 60;
	/** Ticks a menu must stay unchanged before it is read, so every slot has arrived. */
	private static final int SETTLE = 3;
	private static final int COMMAND_GAP = 6;
	private static final int MAX_ADJUST_CLICKS = 24;
	private static final int MAX_ROUNDS = 12;
	private static final int MAX_AUCTION_TRIES = 3;
	private static final int MAX_STACK = 64;

	public record Config(boolean shop, boolean auction, String shopCommand, String auctionCommand, double maxEach, double budget) {
	}

	public record Result(Item item, int bought, double spent, String problem) {
	}

	private record Listing(int slot, int count, double price, double each) {
	}

	private enum Step {
		IDLE, WAIT, SHOP_ROOT, SHOP_PAGE, SHOP_AMOUNT, AUCTION_LIST, AUCTION_CONFIRM, DELIVERY
	}

	private enum Source {
		SHOP, AUCTION
	}

	// What the shop sells, learned page by page: item -> root slot of its category.
	private final Map<Item, Integer> shopCategory = new HashMap<>();
	private final Set<Integer> visited = new HashSet<>();
	private List<Integer> categories;
	private boolean shopRead;
	private String shopCommandRead = "";

	private Step step = Step.IDLE;
	private Item item;
	private int need;
	private Config config;
	private int bought;
	private double spent;
	private int rounds;
	private boolean shopOpen;
	private String shopNote;
	private Result result;

	private int delay;
	private Runnable afterDelay;

	// WAIT: the menu as it was before the action, and what to do once it changes.
	private Step next;
	private int timeout;
	private Runnable onTimeout;
	private int fromId;
	private String fromSig;
	private String lastSig;
	private int stable;
	private boolean watchDelivery;
	/** The slot last clicked: the client shows it picked up until the server answers, which is not a change. */
	private int clicked = -1;
	private int ignore = -1;

	// The purchase in progress.
	private Source source;
	private int category;
	private double unit;
	private int want;
	private int expectAmount;
	private int adjustClicks;
	private Listing listing;
	private int auctionTries;
	private int countBefore;
	private int settle;
	private double pendingCost;

	public boolean busy() {
		return step != Step.IDLE;
	}

	public Item item() {
		return item;
	}

	/** The outcome of the last job, once; null while busy or if already taken. */
	public Result takeResult() {
		Result r = result;
		result = null;
		return r;
	}

	public void start(Item item, int need, Config config) {
		this.item = item;
		this.need = need;
		this.config = config;
		bought = 0;
		spent = 0;
		rounds = 0;
		auctionTries = 0;
		shopNote = null;
		result = null;
		if (!config.shopCommand().equals(shopCommandRead)) forget();
		shopCommandRead = config.shopCommand();
		shopOpen = config.shop() && !(shopRead && !shopCategory.containsKey(item));
		nextRound();
	}

	/** Stops whatever is under way and closes any menu it opened. */
	public void cancel() {
		if (step != Step.IDLE) closeMenu();
		step = Step.IDLE;
		afterDelay = null;
		delay = 0;
	}

	/** Forgets what the shop was found to sell; it is read again on the next purchase. */
	public void forget() {
		shopCategory.clear();
		visited.clear();
		categories = null;
		shopRead = false;
	}

	public void tick() {
		LocalPlayer p = MC.player;
		if (p == null || MC.gameMode == null || MC.getConnection() == null) {
			step = Step.IDLE;
			return;
		}
		if (delay > 0) {
			if (--delay == 0 && afterDelay != null) {
				Runnable r = afterDelay;
				afterDelay = null;
				r.run();
			}
			return;
		}
		switch (step) {
			case WAIT -> waitForChange();
			case SHOP_ROOT -> shopRoot();
			case SHOP_PAGE -> shopPage();
			case SHOP_AMOUNT -> shopAmount();
			case AUCTION_LIST -> auctionList();
			case AUCTION_CONFIRM -> auctionConfirm();
			case DELIVERY -> delivery();
			default -> {
			}
		}
	}

	// ---- rounds ----

	private void nextRound() {
		if (bought >= need) {
			finish(null);
			return;
		}
		if (++rounds > MAX_ROUNDS) {
			finish(null);
			return;
		}
		if (room() <= 0) {
			finish("your inventory is full");
			return;
		}
		if (budgetLeft() < 0.005) {
			finish("the spending cap is reached");
			return;
		}
		if (shopOpen) openShop();
		else if (config.auction()) openAuction();
		else finish(shopNote != null ? shopNote : "/" + command(config.shopCommand()) + " does not sell it, and the auction house is off");
	}

	private void finish(String problem) {
		closeMenu();
		result = new Result(item, bought, spent, bought >= need ? null : problem);
		step = Step.IDLE;
	}

	// ---- shop ----

	private void openShop() {
		source = Source.SHOP;
		closeMenu();
		later(COMMAND_GAP, () -> {
			sendCommand(config.shopCommand());
			expect(Step.SHOP_ROOT, OPEN_TIMEOUT, false, () -> shopFailed("/" + command(config.shopCommand()) + " did not open a menu"));
		});
	}

	private void shopRoot() {
		AbstractContainerMenu menu = menu();
		if (menu == null) {
			shopFailed("the shop closed");
			return;
		}
		int direct = findShopItem(menu);
		if (direct >= 0) {
			buyFromShop(menu, direct);
			return;
		}
		if (categories == null) categories = categories(menu);
		// Straight to the category it was seen in; otherwise the first one not yet read.
		Integer pick = shopCategory.get(item);
		if (pick == null) {
			for (int c : categories) {
				if (!visited.contains(c)) {
					pick = c;
					break;
				}
			}
		}
		if (pick == null) {
			shopRead = true;
			shopFailed(null);
			return;
		}
		category = pick;
		click(menu, pick);
		// A click that changes nothing was not a category; carry on with the next one.
		expect(Step.SHOP_PAGE, CLICK_TIMEOUT, false, () -> {
			visited.add(category);
			step = Step.SHOP_ROOT;
		});
	}

	private void shopPage() {
		AbstractContainerMenu menu = menu();
		if (menu == null) {
			shopFailed("the shop closed");
			return;
		}
		visited.add(category);
		for (int i : containerSlots(menu)) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (!stack.isEmpty() && !Double.isNaN(ShopText.price(ShopText.lore(stack)))) shopCategory.putIfAbsent(stack.getItem(), category);
		}
		int slot = findShopItem(menu);
		if (slot >= 0) {
			buyFromShop(menu, slot);
			return;
		}
		if (Integer.valueOf(category).equals(shopCategory.get(item))) shopCategory.remove(item);
		if (categories != null && visited.containsAll(categories)) {
			shopRead = true;
			shopFailed(null);
		} else {
			openShop();
		}
	}

	private void buyFromShop(AbstractContainerMenu menu, int slot) {
		ItemStack stack = menu.slots.get(slot).getItem();
		List<String> lore = ShopText.lore(stack);
		double price = ShopText.price(lore);
		unit = ShopText.perItem(lore) || stack.getCount() <= 1 ? price : price / stack.getCount();
		if (Double.isNaN(unit) || unit <= 0) {
			shopFailed("could not read the shop's price for it");
			return;
		}
		if (unit > config.maxEach() + 1e-9) {
			shopFailed("the shop asks " + ShopText.format(unit) + " each, over your " + ShopText.format(config.maxEach()) + " limit");
			return;
		}
		want = Math.min(Math.min(need - bought, MAX_STACK), Math.min(room(), (int) Math.floor(budgetLeft() / unit + 1e-9)));
		if (want < 1) {
			finish(room() <= 0 ? "your inventory is full" : "the spending cap is reached");
			return;
		}
		adjustClicks = 0;
		expectAmount = -1;
		countBefore = count();
		click(menu, slot);
		expect(Step.SHOP_AMOUNT, CLICK_TIMEOUT, true, () -> shopFailed("clicking it in the shop did nothing"));
	}

	private void shopAmount() {
		AbstractContainerMenu menu = menu();
		if (menu == null) {
			shopFailed("the shop closed before buying");
			return;
		}
		List<int[]> buttons = new ArrayList<>(); // {slot, kind ordinal, amount}
		int confirm = -1;
		int greenPane = -1;
		for (int i : containerSlots(menu)) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (stack.isEmpty() || stack.is(item)) continue;
			ShopText.Button b = ShopText.button(ShopText.name(stack));
			if (b.kind() == ShopText.Kind.CONFIRM && confirm < 0) confirm = i;
			else if (b.kind() == ShopText.Kind.SET || b.kind() == ShopText.Kind.ADD || b.kind() == ShopText.Kind.BUY) {
				buttons.add(new int[] {i, b.kind().ordinal(), b.amount()});
			} else if (b.kind() == ShopText.Kind.NONE && greenPane < 0 && isGreen(stack)) greenPane = i;
		}
		if (confirm < 0) confirm = greenPane;

		// Buttons that buy a fixed amount outright: the largest that does not overshoot.
		int[] direct = null;
		for (int[] b : buttons) {
			if (b[1] != ShopText.Kind.BUY.ordinal() || b[2] < 1) continue;
			if (b[2] <= want && (direct == null || b[2] > direct[2])) direct = b;
		}
		if (direct != null) {
			pay(menu, direct[0], direct[2]);
			return;
		}

		int current = shownAmount(menu);
		if (current >= 0 && expectAmount >= 0 && current != expectAmount) {
			shopFailed("could not read the amount on the shop's buy screen");
			return;
		}
		if (current < 0) {
			// The amount is not shown anywhere. Only a "set to" button can be trusted then.
			for (int[] b : buttons) {
				if (b[1] == ShopText.Kind.SET.ordinal() && b[2] == want && adjustClicks == 0) {
					adjust(menu, b[0], want);
					return;
				}
			}
			if (buttons.isEmpty() && confirm >= 0) {
				pay(menu, confirm, 1);
				return;
			}
			if (adjustClicks > 0 && confirm >= 0) {
				pay(menu, confirm, want);
				return;
			}
			shopFailed("could not read the amount on the shop's buy screen");
			return;
		}

		if (current != want && adjustClicks < MAX_ADJUST_CLICKS) {
			int[] best = null;
			int bestResult = current;
			for (int[] b : buttons) {
				int to;
				if (b[1] == ShopText.Kind.SET.ordinal()) to = b[2];
				else if (b[1] == ShopText.Kind.ADD.ordinal()) to = current + b[2];
				else continue;
				if (to < 1 || to > MAX_STACK) continue;
				if (Math.abs(want - to) < Math.abs(want - bestResult)) {
					best = b;
					bestResult = to;
				}
			}
			if (best != null) {
				adjust(menu, best[0], bestResult);
				return;
			}
		}
		if (confirm < 0) {
			shopFailed("found no confirm button on the shop's buy screen");
			return;
		}
		if (current > want && (current > room() || current * unit > budgetLeft() + 1e-6)) {
			shopFailed("could not lower the amount on the shop's buy screen");
			return;
		}
		pay(menu, confirm, current);
	}

	private void adjust(AbstractContainerMenu menu, int slot, int result) {
		adjustClicks++;
		expectAmount = result;
		click(menu, slot);
		expect(Step.SHOP_AMOUNT, CLICK_TIMEOUT, false, () -> shopFailed("the shop's amount buttons did nothing"));
	}

	private void pay(AbstractContainerMenu menu, int slot, int amount) {
		double cost = amount * unit;
		if (cost > budgetLeft() + 1e-6) {
			shopFailed("buying " + amount + " would go over your spending cap");
			return;
		}
		pendingCost = -1; // the shop is paid per item that arrives
		countBefore = count();
		click(menu, slot);
		awaitDelivery();
	}

	/** The amount the buy screen shows: written in the item's name or lore, else its stack size. */
	private int shownAmount(AbstractContainerMenu menu) {
		for (int i : containerSlots(menu)) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (!stack.is(item)) continue;
			List<String> lines = new ArrayList<>();
			lines.add(ShopText.name(stack));
			lines.addAll(ShopText.lore(stack));
			int written = ShopText.amount(lines);
			return written >= 0 ? written : stack.getCount();
		}
		return -1;
	}

	private void shopFailed(String why) {
		if (why != null) shopNote = "the shop: " + why;
		shopOpen = false;
		closeMenu();
		if (config.auction()) {
			openAuction();
		} else {
			finish(shopNote != null ? shopNote : "/" + command(config.shopCommand()) + " does not sell it, and the auction house is off");
		}
	}

	// ---- auction house ----

	private void openAuction() {
		source = Source.AUCTION;
		closeMenu();
		if (++auctionTries > MAX_AUCTION_TRIES + MAX_ROUNDS) {
			finish("the auction house kept changing");
			return;
		}
		String search = BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
		String cmd = command(config.auctionCommand()).replace("{item}", search);
		later(COMMAND_GAP, () -> {
			sendCommand(cmd);
			expect(Step.AUCTION_LIST, OPEN_TIMEOUT, false, () -> finish("/" + cmd + " did not open a menu"));
		});
	}

	private void auctionList() {
		AbstractContainerMenu menu = menu();
		if (menu == null) {
			finish("the auction house closed");
			return;
		}
		int left = need - bought;
		int space = room();
		double budget = budgetLeft();
		Listing whole = null;
		Listing part = null;
		double cheapestEach = Double.NaN;
		int seen = 0;
		for (int i : containerSlots(menu)) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (!stack.is(item)) continue;
			List<String> lore = ShopText.lore(stack);
			double price = ShopText.price(lore);
			if (Double.isNaN(price) || price <= 0) continue;
			int n = stack.getCount();
			double total = ShopText.perItem(lore) ? price * n : price;
			double each = total / n;
			seen++;
			if (Double.isNaN(cheapestEach) || each < cheapestEach) cheapestEach = each;
			if (each > config.maxEach() + 1e-9 || total > budget + 1e-6 || n > space) continue;
			Listing l = new Listing(i, n, total, each);
			// One listing that covers the rest of the need: the cheapest in total.
			if (n >= left) {
				if (whole == null || total < whole.price()) whole = l;
			} else if (part == null || each < part.each()) {
				part = l;
			}
		}
		Listing pick;
		if (whole != null && (part == null || whole.price() <= part.each() * left)) pick = whole;
		else pick = part;
		String name = new ItemStack(item).getHoverName().getString();
		if (pick == null) {
			String why;
			if (seen == 0) why = "no " + name + " on the auction house";
			else if (cheapestEach > config.maxEach() + 1e-9) {
				why = "the cheapest " + name + " on the auction house is " + ShopText.format(cheapestEach) + " each, over your "
						+ ShopText.format(config.maxEach()) + " limit";
			} else if (budget < cheapestEach) why = "the spending cap is reached";
			else why = "no auction house listing fits your spending cap and inventory";
			finish(shopNote != null && bought == 0 ? shopNote + "; " + why : why);
			return;
		}
		listing = pick;
		countBefore = count();
		click(menu, pick.slot());
		expect(Step.AUCTION_CONFIRM, CLICK_TIMEOUT, true, () -> finish("clicking the auction listing did nothing"));
	}

	private void auctionConfirm() {
		AbstractContainerMenu menu = menu();
		if (menu == null) {
			finish("the auction house closed before buying");
			return;
		}
		// The buy screen must show the listing that was picked: same item, same count, no dearer.
		boolean same = false;
		boolean dearer = false;
		int confirm = -1;
		int greenPane = -1;
		for (int i : containerSlots(menu)) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (stack.isEmpty()) continue;
			if (stack.is(item) && stack.getCount() == listing.count()) {
				List<String> lore = ShopText.lore(stack);
				double price = ShopText.price(lore);
				if (!Double.isNaN(price) && ShopText.perItem(lore)) price *= stack.getCount();
				if (!Double.isNaN(price) && price > listing.price() + 0.01) dearer = true;
				else same = true;
				continue;
			}
			ShopText.Button b = ShopText.button(ShopText.name(stack));
			if (b.kind() == ShopText.Kind.CONFIRM && confirm < 0) confirm = i;
			else if (b.kind() == ShopText.Kind.NONE && greenPane < 0 && isGreen(stack)) greenPane = i;
		}
		if (confirm < 0) confirm = greenPane;
		if (!same || dearer) {
			// Someone else bought it, or the screen is not what was expected: look again.
			openAuction();
			return;
		}
		if (confirm < 0) {
			finish("found no confirm button on the auction house's buy screen");
			return;
		}
		pendingCost = listing.price();
		countBefore = count();
		click(menu, confirm);
		awaitDelivery();
	}

	// ---- delivery ----

	private void awaitDelivery() {
		step = Step.DELIVERY;
		timeout = DELIVERY_TIMEOUT;
		settle = -1;
	}

	private void delivery() {
		int now = count();
		if (now > countBefore) {
			// Let the rest of a split delivery land before counting it.
			if (settle < 0) settle = SETTLE;
			if (--settle > 0) return;
			int got = now - countBefore;
			bought += got;
			spent += pendingCost >= 0 ? pendingCost : got * unit;
			closeMenu();
			nextRound();
			return;
		}
		if (--timeout > 0) return;
		closeMenu();
		if (source == Source.SHOP) shopFailed("it was confirmed but nothing arrived (not enough money?)");
		else finish("the auction house purchase was confirmed but nothing arrived (not enough money?)");
	}

	// ---- waiting for menus ----

	private void expect(Step then, int ticks, boolean delivery, Runnable onTimeout) {
		AbstractContainerMenu menu = menu();
		ignore = clicked;
		clicked = -1;
		fromId = menu == null ? -1 : menu.containerId;
		fromSig = menu == null ? "" : signature(menu, ignore);
		lastSig = null;
		stable = 0;
		next = then;
		timeout = ticks;
		watchDelivery = delivery;
		this.onTimeout = onTimeout;
		step = Step.WAIT;
	}

	private void waitForChange() {
		if (watchDelivery && count() > countBefore) {
			pendingCost = source == Source.AUCTION ? listing.price() : -1;
			awaitDelivery();
			return;
		}
		AbstractContainerMenu menu = menu();
		// Something on the cursor means the server has not answered the click yet.
		if (menu != null && menu.getCarried().isEmpty()) {
			String sig = signature(menu, menu.containerId == fromId ? ignore : -1);
			boolean changed = menu.containerId != fromId || !sig.equals(fromSig);
			if (changed && !sig.isBlank()) {
				if (sig.equals(lastSig)) stable++;
				else {
					stable = 0;
					lastSig = sig;
				}
				if (stable >= SETTLE) {
					step = next;
					return;
				}
			}
		}
		if (--timeout <= 0) onTimeout.run();
	}

	private void later(int ticks, Runnable action) {
		step = Step.WAIT;
		timeout = Integer.MAX_VALUE;
		delay = ticks;
		afterDelay = action;
	}

	// ---- menu helpers ----

	/** The open server menu, or null when only the player's own inventory is open. */
	private static AbstractContainerMenu menu() {
		LocalPlayer p = MC.player;
		if (p == null || p.containerMenu == null || p.containerMenu == p.inventoryMenu) return null;
		return p.containerMenu;
	}

	private static List<Integer> containerSlots(AbstractContainerMenu menu) {
		List<Integer> list = new ArrayList<>();
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			if (!(slot.container instanceof Inventory)) list.add(i);
		}
		return list;
	}

	private static String signature(AbstractContainerMenu menu, int ignore) {
		StringBuilder sb = new StringBuilder();
		for (int i : containerSlots(menu)) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (stack.isEmpty() || i == ignore) continue;
			sb.append(i).append(':').append(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()).append('x').append(stack.getCount())
					.append(ShopText.name(stack)).append(ShopText.lore(stack)).append(';');
		}
		return sb.toString();
	}

	/** The shop slot holding the item with a readable price; -1 if none. */
	private int findShopItem(AbstractContainerMenu menu) {
		for (int i : containerSlots(menu)) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (stack.is(item) && !Double.isNaN(ShopText.price(ShopText.lore(stack)))) return i;
		}
		return -1;
	}

	/** Root shop slots that look like categories: named items that are not filler or navigation. */
	private static List<Integer> categories(AbstractContainerMenu menu) {
		List<Integer> list = new ArrayList<>();
		for (int i : containerSlots(menu)) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (stack.isEmpty() || isFiller(stack)) continue;
			String name = ShopText.name(stack);
			if (name.isBlank() || ShopText.button(name).kind() == ShopText.Kind.CANCEL) continue;
			if (name.matches(".*\\b(page|search|sort|info|help|balance|refresh)\\b.*")) continue;
			list.add(i);
		}
		return list;
	}

	private static boolean isFiller(ItemStack stack) {
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		return path.endsWith("glass_pane") || path.equals("barrier") || path.equals("air");
	}

	private static boolean isGreen(ItemStack stack) {
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		return path.startsWith("lime_") || path.startsWith("green_") || path.equals("emerald_block");
	}

	private void click(AbstractContainerMenu menu, int slot) {
		clicked = slot;
		MC.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, MC.player);
	}

	private static void closeMenu() {
		LocalPlayer p = MC.player;
		if (p != null && p.containerMenu != p.inventoryMenu) p.closeContainer();
	}

	private static String command(String text) {
		String t = text.trim();
		return t.startsWith("/") ? t.substring(1) : t;
	}

	private void sendCommand(String text) {
		clicked = -1;
		MC.getConnection().sendCommand(command(text));
	}

	private double budgetLeft() {
		return config.budget() - spent;
	}

	/** How many of the item are carried, hotbar and offhand included. */
	private int count() {
		Inventory inv = MC.player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.is(item)) n += stack.getCount();
		}
		return n;
	}

	/** How many more of the item the main inventory and hotbar can take. */
	private int room() {
		Inventory inv = MC.player.getInventory();
		int max = item.getDefaultMaxStackSize();
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) n += max;
			else if (stack.is(item)) n += Math.max(0, max - stack.getCount());
		}
		return n;
	}
}
