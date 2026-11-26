package app.mfilm.darkroom;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A roll log the camera's filmroll module writes into the folder:
 *
 * <pre>
 * # ML film mode roll log (filmroll module)
 * roll = 013
 * film = redscale100
 * exposures = 24
 * loaded = 2026-11-25 16:07:36
 *
 * 1 IMG_3657.CR2 2026-11-25 16:08:08 f/4.5
 * ...
 * # rewound 2026-11-25 16:13:15 after 6 of 24 frames
 * </pre>
 */
final class Roll {

    String number = "";
    String filmKey = "";
    int exposures;
    final List<String> files = new ArrayList<>();

    static Roll parse(String text) {
        Roll roll = new Roll();
        if (text == null) return roll;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).trim().indexOf(' ') < 0) {
                String name = line.substring(0, eq).trim().toLowerCase(Locale.US);
                String value = line.substring(eq + 1).trim();
                if (name.equals("roll")) roll.number = value;
                else if (name.equals("film")) roll.filmKey = value;
                else if (name.equals("exposures")) roll.exposures = atoi(value);
                continue;
            }
            /* "1 IMG_3657.CR2 2026-11-25 16:08:08 f/4.5" */
            String[] p = line.split("\\s+");
            if (p.length >= 2 && p[1].toLowerCase(Locale.US).endsWith(".cr2")) {
                roll.files.add(p[1]);
            }
        }
        return roll;
    }

    private static int atoi(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
