package app.sprout.sandbox.domain;

import java.util.List;
import java.util.Optional;

/**
 * The fifteen fictional people visitors can explore Sprout as: five women, five men, and five
 * non-binary people or people of other genders, from across India. Each way of investing appears once
 * in every group, so no group is given a stereotype. Everything about them is made up.
 */
public final class Personas {

    public enum Group { WOMEN, MEN, NON_BINARY_AND_OTHER }

    /** How a person invests, which decides what they do each trading session. */
    public enum Style { STEADY_PLANS, ROUND_UPS, GOAL_SAVER, NEW_INVESTOR, EXPLORER }

    /**
     * One person. {@code salary} is their monthly pay in rupees; {@code symbol} is the share their plan
     * or pot invests in; {@code referredBy} names the friend whose code they entered, if any.
     */
    public record Persona(String id, String name, String pronouns, Group group, String city, String story, Style style,
                          String dateOfBirth, int salary, String symbol, String goal, int goalTarget, String referredBy) {}

    public static final List<Persona> ALL = List.of(
            new Persona("meera", "Meera Iyer", "she/her", Group.WOMEN, "Chennai",
                    "A physiotherapist saving towards her own clinic. Her plan buys on the 5th, the day after payday.",
                    Style.STEADY_PLANS, "1993-04-12", 65000, "SAPLING", null, 0, null),
            new Persona("ananya", "Ananya Bose", "she/her", Group.WOMEN, "Kolkata",
                    "A graphic designer who rounds up every chai and metro ride into a fund for a trip to Ladakh.",
                    Style.ROUND_UPS, "1998-11-03", 48000, "KOSHA", "Ladakh trip", 60000, null),
            new Persona("fatima", "Fatima Shaikh", "she/her", Group.WOMEN, "Mumbai",
                    "A software tester putting money aside every few weeks for a deposit on a flat.",
                    Style.GOAL_SAVER, "1995-07-21", 72000, "HARBOR", "Flat deposit", 400000, null),
            new Persona("harpreet", "Harpreet Kaur", "she/her", Group.WOMEN, "Amritsar",
                    "A nursing student who made her first investment this year and is learning as she goes.",
                    Style.NEW_INVESTOR, "2002-02-08", 25000, "IRONLEAF", null, 0, "meera"),
            new Persona("lalitha", "Lalitha Reddy", "she/her", Group.WOMEN, "Hyderabad",
                    "A retired teacher who likes to spread her money across many different companies.",
                    Style.EXPLORER, "1961-09-30", 55000, "TEALPWR", null, 0, null),

            new Persona("arjun", "Arjun Nair", "he/him", Group.MEN, "Kochi",
                    "A marine engineer saving for his parents' anniversary trip.",
                    Style.GOAL_SAVER, "1990-05-17", 85000, "GRIDLINE", "Anniversary trip", 150000, null),
            new Persona("rohan", "Rohan Deshmukh", "he/him", Group.MEN, "Pune",
                    "A data analyst with a plan for every month and a long horizon.",
                    Style.STEADY_PLANS, "1994-01-26", 78000, "BYTEFARM", null, 0, null),
            new Persona("imran", "Imran Qureshi", "he/him", Group.MEN, "Lucknow",
                    "A chef whose everyday spends round up into a fund for a course in pastry.",
                    Style.ROUND_UPS, "1996-08-14", 42000, "MARIGOLD", "Pastry course", 80000, null),
            new Persona("tenzing", "Tenzing Bhutia", "he/him", Group.MEN, "Gangtok",
                    "A trekking guide who started investing this season, between treks.",
                    Style.NEW_INVESTOR, "1999-12-02", 30000, "SUNROOT", null, 0, "rohan"),
            new Persona("vikram", "Vikram Rathore", "he/him", Group.MEN, "Jaipur",
                    "A small-business owner who enjoys reading about companies before buying a little of each.",
                    Style.EXPLORER, "1985-03-09", 90000, "RAILYARD", null, 0, null),

            new Persona("kiran", "Kiran Joshi", "they/them", Group.NON_BINARY_AND_OTHER, "Dehradun",
                    "A wildlife photographer rounding up everyday spends towards a new lens.",
                    Style.ROUND_UPS, "1997-06-25", 45000, "THREADS", "New lens", 70000, null),
            new Persona("sam", "Sam Fernandes", "they/them", Group.NON_BINARY_AND_OTHER, "Goa",
                    "A musician building a steady monthly plan alongside gig income.",
                    Style.STEADY_PLANS, "1992-10-18", 52000, "CLOUDLEAF", null, 0, null),
            new Persona("noor", "Noor Siddiqui", "she/they", Group.NON_BINARY_AND_OTHER, "Delhi",
                    "A UX researcher saving for a master's degree abroad.",
                    Style.GOAL_SAVER, "1996-02-29", 68000, "LEDGERCO", "Master's degree", 300000, null),
            new Persona("rhea", "Rhea Das", "they/them", Group.NON_BINARY_AND_OTHER, "Guwahati",
                    "A dance teacher who made their first investment last month.",
                    Style.NEW_INVESTOR, "2000-04-04", 28000, "CHAIWALA", null, 0, "sam"),
            new Persona("ari", "Ari Menon", "he/they", Group.NON_BINARY_AND_OTHER, "Bengaluru",
                    "A game developer trying a little of everything, one share at a time.",
                    Style.EXPLORER, "1995-12-11", 95000, "NEURONET", null, 0, null));

    private Personas() {}

    public static Optional<Persona> byId(String id) {
        return ALL.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** A fictional PAN for the person, unique among them: an individual's (fourth letter P), never a real one's pattern of issue. */
    static String pan(Persona p) {
        int i = ALL.indexOf(p);
        char surname = Character.toUpperCase(p.name().substring(p.name().lastIndexOf(' ') + 1).charAt(0));
        return "SBX" + "P" + surname + String.format("%04d", 9000 + i) + "Z";
    }

    public static String label(Group g) {
        return switch (g) {
            case WOMEN -> "A woman";
            case MEN -> "A man";
            case NON_BINARY_AND_OTHER -> "Non-binary or another gender";
        };
    }
}
