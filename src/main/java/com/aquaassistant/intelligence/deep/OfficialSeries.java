package com.aquaassistant.intelligence.deep;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Official pH series of the USGS stations that the backend imports into the shared database (reference_readings,
 * every 15 minutes for the featured stations, kept 30 days). They are real measurements of rivers, used to show how
 * the LSTM network works with real data when the person does not have days of their own measurements yet.
 */
@Component
public class OfficialSeries {

    /** A station with enough readings for the network (a little more than a week every hour). */
    static final int MIN_READINGS = 200;
    private static final Pattern ID = Pattern.compile("USGS-[0-9A-Za-z-]{4,30}");

    public record Station(String id, String name, String state, int readings, Instant from, Instant to) {
    }

    public record Reading(Instant at, double ph) {
    }

    private final JdbcTemplate jdbc;

    public OfficialSeries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public List<Station> stations() {
        return jdbc.query("""
            select s.id, s.name, s.state, count(r.id) as n, min(r.measured_at) as first_at, max(r.measured_at) as last_at
            from reference_stations s join reference_readings r on r.station_id = s.id
            group by s.id, s.name, s.state having count(r.id) >= ? order by s.name""",
            (rs, i) -> new Station(rs.getString("id"), rs.getString("name"), rs.getString("state"), rs.getInt("n"),
                rs.getTimestamp("first_at").toInstant(), rs.getTimestamp("last_at").toInstant()), MIN_READINGS);
    }

    public List<Reading> readings(String stationId) {
        return jdbc.query("select measured_at, ph from reference_readings where station_id = ? order by measured_at",
            (rs, i) -> new Reading(rs.getTimestamp("measured_at").toInstant(), rs.getDouble("ph")), stationId);
    }

    public String name(String stationId) {
        List<String> names = jdbc.queryForList("select name from reference_stations where id = ?", String.class, stationId);
        return names.isEmpty() ? null : names.get(0);
    }

    static Timestamp ts(Instant at) {
        return Timestamp.from(at);
    }
}
