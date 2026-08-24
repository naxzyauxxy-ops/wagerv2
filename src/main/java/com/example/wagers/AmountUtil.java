package com.example.wagers;

/**
 * Parses shorthand money amounts typed by players:
 *   500      -> 500
 *   5k       -> 5,000
 *   5m / 5M  -> 5,000,000
 *   2.5b     -> 2,500,000,000
 *   1,000    -> 1000   (commas ignored)
 */
public final class AmountUtil {

    private AmountUtil() { }

    /** @return parsed amount, or -1 if the input isn't a valid amount. */
    public static double parse(String input) {
        if (input == null || input.isEmpty()) return -1;

        String s = input.trim().toLowerCase()
                .replace(",", "")
                .replace("$", "");
        if (s.isEmpty()) return -1;

        double multiplier = 1;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k' -> multiplier = 1_000D;
            case 'm' -> multiplier = 1_000_000D;
            case 'b' -> multiplier = 1_000_000_000D;
            case 't' -> multiplier = 1_000_000_000_000D;
            case 'q' -> multiplier = 1_000_000_000_000_000D;
            default -> { }
        }
        if (multiplier != 1) {
            s = s.substring(0, s.length() - 1);
            if (s.isEmpty()) return -1;
        }

        double value;
        try {
            value = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return -1;
        }
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) return -1;

        double result = value * multiplier;
        if (Double.isInfinite(result)) return -1;
        // Round to 2dp so 1.005k doesn't create fractions of a cent
        return Math.round(result * 100D) / 100D;
    }
}
