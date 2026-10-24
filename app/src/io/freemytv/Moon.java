package io.freemytv;

import java.util.Calendar;

/**
 * Today's moonrise and moonset at a place, worked out on the TV (no network): SunCalc's method by Vladimir Agafonkin
 * (BSD), from "Astronomy on the Personal Computer" (Montenbruck, Pfleger). Good to a few minutes.
 */
final class Moon {
    private Moon() {}

    private static final double RAD = Math.PI / 180, DAY_MS = 86_400_000d, J1970 = 2440588, J2000 = 2451545;
    private static final double E = RAD * 23.4397;   // obliquity of the Earth

    /** {rise, set} in epoch ms for the local day that holds `now`; 0 where the moon doesn't rise or set that day. */
    static long[] times(long now, double lat, double lng) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long t = c.getTimeInMillis();
        double hc = 0.133 * RAD;
        double h0 = altitude(t, lat, lng) - hc;
        double rise = Double.NaN, set = Double.NaN;
        // Two hours at a time: a parabola through three heights, and where it crosses the horizon.
        for (int i = 1; i <= 24; i += 2) {
            double h1 = altitude(t + i * 3_600_000L, lat, lng) - hc;
            double h2 = altitude(t + (i + 1) * 3_600_000L, lat, lng) - hc;
            double a = (h0 + h2) / 2 - h1, b = (h2 - h0) / 2;
            double xe = -b / (2 * a), ye = (a * xe + b) * xe + h1, d = b * b - 4 * a * h1;
            int roots = 0;
            double x1 = 0, x2 = 0;
            if (d >= 0) {
                double dx = Math.sqrt(d) / (Math.abs(a) * 2);
                x1 = xe - dx;
                x2 = xe + dx;
                if (Math.abs(x1) <= 1) roots++;
                if (Math.abs(x2) <= 1) roots++;
                if (x1 < -1) x1 = x2;
            }
            if (roots == 1) {
                if (h0 < 0) rise = i + x1;
                else set = i + x1;
            } else if (roots == 2) {
                rise = i + (ye < 0 ? x2 : x1);
                set = i + (ye < 0 ? x1 : x2);
            }
            if (!Double.isNaN(rise) && !Double.isNaN(set)) break;
            h0 = h2;
        }
        return new long[] {
                Double.isNaN(rise) ? 0 : t + Math.round(rise * 3_600_000d),
                Double.isNaN(set) ? 0 : t + Math.round(set * 3_600_000d)};
    }

    /** The moon's height above the horizon (radians, with refraction) at `ms`. */
    private static double altitude(long ms, double lat, double lng) {
        double d = ms / DAY_MS - 0.5 + J1970 - J2000;
        double lw = RAD * -lng, phi = RAD * lat;
        double L = RAD * (218.316 + 13.176396 * d), M = RAD * (134.963 + 13.064993 * d), F = RAD * (93.272 + 13.229350 * d);
        double l = L + RAD * 6.289 * Math.sin(M), b = RAD * 5.128 * Math.sin(F);
        double ra = Math.atan2(Math.sin(l) * Math.cos(E) - Math.tan(b) * Math.sin(E), Math.cos(l));
        double dec = Math.asin(Math.sin(b) * Math.cos(E) + Math.cos(b) * Math.sin(E) * Math.sin(l));
        double H = RAD * (280.16 + 360.9856235 * d) - lw - ra;
        double h = Math.asin(Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(H));
        double hr = Math.max(h, 0);
        return h + 0.0002967 / Math.tan(hr + 0.00312536 / (hr + 0.0890118));
    }
}
