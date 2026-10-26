package com.zen.tvhome;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Lumière du moment : les deux moments de la journée qui encadrent l'heure actuelle et la proportion
 * du second, d'après le vrai lever / coucher du soleil (formules NOAA) pour le fuseau horaire de la télé.
 */
final class DayPhase {

    /** Ville de référence (latitude, longitude) par fuseau horaire ; sinon estimation depuis le décalage UTC. */
    private static final Map<String, double[]> ZONES = new HashMap<>();

    static {
        Object[][] z = {
                {"Europe/Paris", 48.85, 2.35}, {"Europe/London", 51.5, -0.12}, {"Europe/Dublin", 53.35, -6.26},
                {"Europe/Lisbon", 38.72, -9.14}, {"Europe/Madrid", 40.42, -3.7}, {"Europe/Brussels", 50.85, 4.35},
                {"Europe/Amsterdam", 52.37, 4.9}, {"Europe/Luxembourg", 49.61, 6.13}, {"Europe/Zurich", 47.37, 8.54},
                {"Europe/Berlin", 52.52, 13.4}, {"Europe/Rome", 41.9, 12.5}, {"Europe/Vienna", 48.2, 16.37},
                {"Europe/Prague", 50.08, 14.44}, {"Europe/Warsaw", 52.23, 21.01}, {"Europe/Stockholm", 59.33, 18.07},
                {"Europe/Oslo", 59.91, 10.75}, {"Europe/Copenhagen", 55.68, 12.57}, {"Europe/Helsinki", 60.17, 24.94},
                {"Europe/Athens", 37.98, 23.73}, {"Europe/Istanbul", 41.01, 28.98}, {"Europe/Moscow", 55.75, 37.62},
                {"Europe/Bucharest", 44.43, 26.1}, {"Europe/Kiev", 50.45, 30.52}, {"Europe/Kyiv", 50.45, 30.52},
                {"Africa/Casablanca", 33.57, -7.59}, {"Africa/Algiers", 36.75, 3.06}, {"Africa/Tunis", 36.8, 10.18},
                {"Africa/Cairo", 30.04, 31.24}, {"Africa/Lagos", 6.52, 3.38}, {"Africa/Abidjan", 5.36, -4.01},
                {"Africa/Dakar", 14.69, -17.44}, {"Africa/Nairobi", -1.29, 36.82}, {"Africa/Johannesburg", -26.2, 28.05},
                {"America/New_York", 40.71, -74.0}, {"America/Toronto", 43.65, -79.38}, {"America/Montreal", 45.5, -73.57},
                {"America/Chicago", 41.88, -87.63}, {"America/Denver", 39.74, -104.99}, {"America/Phoenix", 33.45, -112.07},
                {"America/Los_Angeles", 34.05, -118.24}, {"America/Vancouver", 49.28, -123.12},
                {"America/Mexico_City", 19.43, -99.13}, {"America/Bogota", 4.71, -74.07}, {"America/Lima", -12.05, -77.04},
                {"America/Santiago", -33.45, -70.67}, {"America/Argentina/Buenos_Aires", -34.6, -58.38},
                {"America/Sao_Paulo", -23.55, -46.63}, {"America/Martinique", 14.6, -61.07},
                {"America/Guadeloupe", 16.24, -61.53}, {"America/Cayenne", 4.92, -52.33},
                {"Indian/Reunion", -20.88, 55.45}, {"Pacific/Tahiti", -17.53, -149.57}, {"Pacific/Noumea", -22.27, 166.44},
                {"Asia/Dubai", 25.2, 55.27}, {"Asia/Kolkata", 28.61, 77.21}, {"Asia/Bangkok", 13.76, 100.5},
                {"Asia/Singapore", 1.35, 103.82}, {"Asia/Shanghai", 31.23, 121.47}, {"Asia/Hong_Kong", 22.32, 114.17},
                {"Asia/Tokyo", 35.68, 139.69}, {"Asia/Seoul", 37.57, 126.98}, {"Australia/Sydney", -33.87, 151.21},
                {"Australia/Melbourne", -37.81, 144.96}, {"Australia/Perth", -31.95, 115.86},
                {"Pacific/Auckland", -36.85, 174.76},
        };
        for (Object[] e : z) ZONES.put((String) e[0], new double[]{(Double) e[1], (Double) e[2]});
    }

    static double[] location() {
        TimeZone tz = TimeZone.getDefault();
        double[] known = ZONES.get(tz.getID());
        if (known != null) return known;
        String id = tz.getID();
        boolean south = id.startsWith("Australia/") || id.startsWith("Antarctica/") || id.contains("Sao_Paulo")
                || id.contains("Argentina") || id.contains("Santiago") || id.startsWith("Pacific/Auckland");
        return new double[]{south ? -30 : 45, tz.getRawOffset() / 3_600_000.0 * 15};
    }

    final String from, to;
    final float mix;

    private DayPhase(String from, String to, float mix) {
        this.from = from;
        this.to = to;
        this.mix = mix;
    }

    static DayPhase now() {
        Calendar c = Calendar.getInstance();
        double m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0;
        double[] sun = sunriseSunset(c);
        double r = sun[0], s = sun[1];
        List<double[]> times = new ArrayList<>();
        String[] names = {"night", "bluedawn", "sunrise", "morning", "noon", "afternoon", "sunset", "bluedusk", "night"};
        double[] at = {r - 100, r - 35, r + 10, r + 120, (r + s) / 2, s - 120, s - 15, s + 30, s + 100};
        for (int i = 0; i < at.length; i++) times.add(new double[]{at[i], i});
        Collections.sort(times, (x, y) -> Double.compare(x[0], y[0]));
        if (m <= times.get(0)[0] || m >= times.get(times.size() - 1)[0]) return new DayPhase("night", "night", 0);
        for (int i = 0; i < times.size() - 1; i++) {
            double[] a = times.get(i), b = times.get(i + 1);
            if (m >= a[0] && m < b[0]) {
                float t = (float) ((m - a[0]) / Math.max(1, b[0] - a[0]));
                return new DayPhase(names[(int) a[1]], names[(int) b[1]], t);
            }
        }
        return new DayPhase("noon", "noon", 0);
    }

    /** Lever et coucher en minutes depuis minuit, heure locale. */
    static double[] sunriseSunset(Calendar c) {
        int n = c.get(Calendar.DAY_OF_YEAR);
        double g = 2 * Math.PI / 365 * (n - 1);
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g)
                - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g)
                - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double[] loc = location();
        double lat = Math.toRadians(loc[0]), lon = loc[1];
        double cosHa = Math.cos(Math.toRadians(90.833)) / (Math.cos(lat) * Math.cos(decl))
                - Math.tan(lat) * Math.tan(decl);
        double ha = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosHa))));
        double offset = TimeZone.getDefault().getOffset(c.getTimeInMillis()) / 60000.0;
        double rise = 720 - 4 * (lon + ha) - eqTime + offset;
        double set = 720 - 4 * (lon - ha) - eqTime + offset;
        return new double[]{rise, set};
    }
}
