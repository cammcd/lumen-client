package dev.lumen.client.schematic;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Reads what server shop menus write on their items: button names, prices and amounts.
 * Servers style this text in many ways, so everything is first folded to plain lower
 * case: colour codes go, small-caps letters (ᴄᴏɴꜰɪʀᴍ) and full-width characters become
 * ordinary ones.
 */
public final class ShopText {
	private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
	private static final Pattern MONEY = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*([kmbt])?(?![a-z])");
	private static final Pattern AMOUNT = Pattern.compile("(?:amount|quantity|qty)\\D{0,4}(\\d+)");
	private static final Pattern CANCEL = Pattern.compile("\\b(cancel|back|close|deny|decline|return|exit)\\b|^no$");
	private static final Pattern SET = Pattern.compile("\\bset\\b(?:\\s+amount)?(?:\\s+to)?\\s*(\\d+)");
	private static final Pattern ADD = Pattern.compile("(?:\\b(?:add|increase|plus)\\s*\\+?\\s*|^\\+\\s*)(\\d+)");
	private static final Pattern REMOVE = Pattern.compile("(?:\\b(?:remove|decrease|subtract|minus|take)\\s*-?\\s*|^[-−]\\s*)(\\d+)");
	private static final Pattern BUY_AMOUNT = Pattern.compile("\\b(?:buy|purchase)\\s*x?(\\d+)");
	private static final Pattern CONFIRM = Pattern.compile("\\b(confirm|purchase|buy|accept|yes)\\b");

	public enum Kind {
		NONE, CANCEL, SET, ADD, BUY, CONFIRM
	}

	/** What clicking a menu item named this would do, as far as its name says. */
	public record Button(Kind kind, int amount) {
		static final Button NONE = new Button(Kind.NONE, 0);
	}

	private ShopText() {
	}

	/** Plain lower case: no colour codes, no small caps, single spaces. */
	public static String normalize(String text) {
		if (text == null) return "";
		String s = ChatFormatting.stripFormatting(text);
		if (s == null) return "";
		s = Normalizer.normalize(s, Normalizer.Form.NFKC);
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			int small = SMALL_CAPS.indexOf(c);
			out.append(small >= 0 ? (char) ('a' + small) : c);
		}
		return out.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
	}

	public static String name(ItemStack stack) {
		return normalize(stack.getHoverName().getString());
	}

	public static List<String> lore(ItemStack stack) {
		List<String> lines = new ArrayList<>();
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return lines;
		for (Component line : lore.lines()) lines.add(normalize(line.getString()));
		return lines;
	}

	/** A sum of money written as 50, $1,250, 2.5k, $1.5M and the like; NaN if none. */
	public static double money(String text) {
		Matcher m = MONEY.matcher(normalize(text));
		if (!m.find()) return Double.NaN;
		double value;
		try {
			value = Double.parseDouble(m.group(1).replace(",", ""));
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
		String suffix = m.group(2);
		if (suffix != null) {
			value *= switch (suffix) {
				case "k" -> 1e3;
				case "m" -> 1e6;
				case "b" -> 1e9;
				default -> 1e12;
			};
		}
		return value;
	}

	/**
	 * The buy price in a listing's lore: a line about price or cost first, then any line
	 * with a $ in it. Lines about selling or the player's balance are skipped. NaN if none.
	 */
	public static double price(List<String> lore) {
		for (String line : lore) {
			if (skip(line) || !(line.contains("price") || line.contains("cost"))) continue;
			int colon = line.indexOf(':');
			double value = money(colon >= 0 ? line.substring(colon + 1) : line);
			if (!Double.isNaN(value)) return value;
		}
		for (String line : lore) {
			int dollar = line.indexOf('$');
			if (skip(line) || dollar < 0) continue;
			double value = money(line.substring(dollar + 1));
			if (Double.isNaN(value)) value = money(line);
			if (!Double.isNaN(value)) return value;
		}
		return Double.NaN;
	}

	private static boolean skip(String line) {
		return line.contains("sell") || line.contains("balance") || line.contains("worth");
	}

	/** True when a price line says it is per item ("each", "per item", "/ea"). */
	public static boolean perItem(List<String> lore) {
		for (String line : lore) {
			if (line.contains("each") || line.contains("per item") || line.contains("/ea") || line.contains("per 1")) return true;
		}
		return false;
	}

	/** An amount written in a name or lore, like "Amount: 12"; -1 if none. */
	public static int amount(List<String> lines) {
		for (String line : lines) {
			Matcher m = AMOUNT.matcher(line);
			if (m.find()) return Integer.parseInt(m.group(1));
		}
		return -1;
	}

	public static Button button(String name) {
		if (name.isBlank()) return Button.NONE;
		if (CANCEL.matcher(name).find()) return new Button(Kind.CANCEL, 0);
		Matcher m = SET.matcher(name);
		if (m.find()) return new Button(Kind.SET, Integer.parseInt(m.group(1)));
		m = ADD.matcher(name);
		if (m.find()) return new Button(Kind.ADD, Integer.parseInt(m.group(1)));
		m = REMOVE.matcher(name);
		if (m.find()) return new Button(Kind.ADD, -Integer.parseInt(m.group(1)));
		m = BUY_AMOUNT.matcher(name);
		if (m.find()) {
			int n = Integer.parseInt(m.group(1));
			return new Button(Kind.BUY, name.contains("stack") ? n * 64 : n);
		}
		if (CONFIRM.matcher(name).find()) return new Button(Kind.CONFIRM, 0);
		return Button.NONE;
	}

	/** $1,250 or $12.50, for chat messages. */
	public static String format(double money) {
		DecimalFormat f = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
		return "$" + f.format(money);
	}
}
