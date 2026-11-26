package app.mfilm.darkroom;

import java.util.Locale;

/**
 * One film on the shelf, as the camera's FOSFILMS.BIN describes it: the name
 * the shelf shows, the blurb under it, and the colours of its canister.
 */
final class Film {

    final int index;
    final String brand, name, process, group, key, desc, shortname;
    final int iso;
    final int grain, punch, colour, warmth;
    final int bg, band, accent, ink;        // the canister

    Film(int index, String info, String detail) {
        this.index = index;
        String[] i = info == null ? new String[0] : info.split("\\|", -1);
        String[] d = detail == null ? new String[0] : detail.split("\\|", -1);
        brand = at(i, 0).trim();
        name = at(i, 1).trim();
        iso = number(at(i, 2));
        process = at(i, 3).trim();
        group = at(i, 4).trim();
        key = at(i, 5).trim();
        desc = at(d, 0).trim();
        shortname = at(d, 1).trim();
        grain = number(at(d, 2));
        punch = number(at(d, 3));
        colour = number(at(d, 4));
        warmth = number(at(d, 5));
        int[] c = colours(brand, group);
        bg = c[0];
        band = c[1];
        accent = c[2];
        ink = c[3];
    }

    /** "Kodak Portra 400 · 400 ISO · C-41" */
    String label() {
        String full = (brand + " " + name).trim();
        return full + " · " + iso + " ISO · " + process;
    }

    /** the canister's own type, as the box prints it */
    String boxSpeed() {
        return String.valueOf(iso);
    }

    private static String at(String[] a, int i) {
        return i < a.length && a[i] != null ? a[i] : "";
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * The colours the camera paints a canister with. The firmware keeps them
     * as palette entries in FOSFILMS.BIN; on the phone we take them from the
     * brand, which lands on the same box look.
     */
    private static int[] colours(String brand, String group) {
        String b = brand == null ? "" : brand.toUpperCase(Locale.US);
        if (b.contains("KODAK")) return new int[]{0xFFF4E4BD, 0xFFC8102E, 0xFFE9B93C, 0xFF241A12};
        if (b.contains("FUJI")) return new int[]{0xFFF1F5EE, 0xFF0E7A3C, 0xFFC8102E, 0xFF12160F};
        if (b.contains("ILFORD")) return new int[]{0xFFF3F3F1, 0xFF161616, 0xFF8D8D8D, 0xFF111111};
        if (b.contains("AGFA")) return new int[]{0xFFE9ECEF, 0xFF003C8F, 0xFFD8232A, 0xFF0D1526};
        if (b.contains("ROLLEI")) return new int[]{0xFF1B1B1B, 0xFFEDEDED, 0xFFC9A227, 0xFFF2F2F2};
        if (b.contains("LOMO")) return new int[]{0xFF121212, 0xFF7C4DFF, 0xFFE0A33E, 0xFFF5F5F5};
        if (b.contains("CINESTILL") || b.contains("CINE")) return new int[]{0xFF171717, 0xFFB3252C, 0xFFE6C15A, 0xFFF7F3EA};
        if ("B&W".equals(group)) return new int[]{0xFFF2F2F0, 0xFF151515, 0xFF8F8F8F, 0xFF121212};
        if ("SLIDE".equals(group)) return new int[]{0xFF1C2B45, 0xFFE3B34A, 0xFF3E7CC4, 0xFFF3F6FA};
        if ("CINE".equals(group)) return new int[]{0xFF171717, 0xFFB3252C, 0xFFE6C15A, 0xFFF7F3EA};
        return new int[]{0xFFF1E3C4, 0xFFC0642A, 0xFF8A6E4B, 0xFF221A12};
    }
}
