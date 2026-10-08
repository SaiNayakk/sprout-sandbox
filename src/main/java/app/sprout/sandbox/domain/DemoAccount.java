package app.sprout.sandbox.domain;

import java.util.List;
import java.util.UUID;

/**
 * One demo account, and what every demo account is like. They are all the same kind of customer (nothing
 * about who a visitor is gets chosen or assumed): paid monthly, a monthly plan, UPI spends rounded up into a
 * holiday pot, now and then a share bought or sold. Everything about them is made up.
 */
public record DemoAccount(UUID id, long number) {

    /** Monthly pay, in rupees. */
    static final int SALARY = 60000;
    static final String DATE_OF_BIRTH = "1995-06-15";
    /** The plan: a modestly priced share, bought on the 5th, the day after payday. */
    static final String PLAN_SYMBOL = "KOSHA";
    static final int PLAN_AMOUNT = 6000;
    /** The pot round-ups fill: a modestly priced share, so round-ups soon become whole shares and the streak grows. */
    static final String POT_NAME = "Holiday fund";
    static final int POT_TARGET = 60000;
    static final String POT_SYMBOL = "THREADS";
    /** What the odd extra purchase is chosen from: the cheaper end of the market. */
    static final List<String> EVERYDAY_SHARES = List.of("SUNROOT", "THREADS", "IRONLEAF", "NIGHTOWL", "KOSHA", "GRIDLINE");
    static final List<String> MERCHANTS = List.of("monsoonchai@sproutbank", "tiffinbox@sproutbank", "kiranacorner@sproutbank",
            "citymetro@sproutbank", "bookworm@sproutbank", "rechargehub@sproutbank");

    /**
     * Letters for the PAN's fifth character. It is never one the v1 personas used (B, D, F, I, J, K, M, N,
     * Q, R, S), so a demo account's PAN can't collide with theirs.
     */
    private static final String PAN_LETTERS = "ACEGHLOPTUVWXYZ";

    /** The name on its bank account and KYC; the visitor's chosen name is what Sprout calls them. */
    String legalName() {
        return "Demo Customer " + number;
    }

    /** A fictional individual's PAN (fourth letter P), unique per account, never a real one's pattern of issue. */
    String pan() {
        char letter = PAN_LETTERS.charAt((int) ((number / 10000) % PAN_LETTERS.length()));
        return "SBXP" + letter + String.format("%04d", number % 10000) + "Z";
    }

    String nickname() {
        return "Saver " + number;
    }
}
