"""
drive_monitor.py
================================================================================
Single-file Python core for Dasher Monitor (Chaquopy).
Implements the logic described in the product report:

  1. Trip Life Cycle (auto start / arrive / end state machine)
  2. Offer Intelligence (Smart Score engine, 5-factor)
  3. One-Tap Instant Pinpoint (RoadWarrior fallback buffer)
  4. Message Intelligence (customer message parsing / auto-attach)
  5. Safety scoring (harsh accel/brake/speeding)
  6. SQLite persistence (100% offline, no cloud)

Java/Kotlin calls into this module via Chaquopy, e.g.:

    Python py = Python.getInstance();
    PyObject monitor = py.getModule("drive_monitor");
    PyObject engine = monitor.callAttr("get_engine", context.getFilesDir().getPath());
    engine.callAttr("on_gps_update", lat, lon, speedKmh, timestampMs);
    engine.callAttr("on_notification", packageName, title, text, timestampMs);
"""

import sqlite3
import time
import math
import json
import os
import re
from datetime import datetime, timedelta

# ------------------------------------------------------------------------- #
# Constants (thresholds from the report)
# ------------------------------------------------------------------------- #
TRIP_START_SPEED_KMH = 10.0
TRIP_START_HOLD_SECONDS = 10
TRIP_STOP_SPEED_KMH = 1.0
TRIP_STOP_HOLD_SECONDS = 60
TRIP_END_PARK_SECONDS = 5 * 60
ARRIVAL_GEOFENCE_METERS = 50
# "Getting close" radius for the RoadWarrior quick-navigation icon --
# deliberately much larger than the tight 50m arrival geofence, since the
# whole point is a heads-up WHILE STILL APPROACHING (to pull up navigation
# for a tricky apartment complex/gated community before you're already
# there), not just confirmation after arrival. Same honesty note as every
# other threshold in this file: a reasonable-sounding guess, not
# empirically tuned against real usage.
APPROACHING_RADIUS_METERS = 500
MAJOR_DELAY_SECONDS = 2 * 60

# Walking-speed detection (for the purple "walking" status dot, DASHER
# mode only): DEFAULT_WALKING_SPEED_THRESHOLD_KMH is a physically-grounded
# starting guess (average adult walking pace is ~5 km/h, brisk walking up
# to ~7-8 km/h) -- not arbitrary, but still a guess until real samples
# exist. Once WALKING_SPEED_MIN_SAMPLES_TO_LEARN real samples are
# recorded (see TripManager._record_walking_speed_sample), the learned
# average replaces this default, same pattern as delivery speed and
# deadhead. WALKING_SPEED_MIN_KMH is a floor -- below this, it's
# indistinguishable from GPS noise on a stationary phone, not real walking.
DEFAULT_WALKING_SPEED_THRESHOLD_KMH = 8.0
WALKING_SPEED_MIN_KMH = 0.5
WALKING_SPEED_MIN_SAMPLES_TO_LEARN = 10

# Retrospective classification (see TripManager.is_walking_pace): a
# single slow GPS reading can't tell "parked, now walking" apart from
# "car briefly slowed in traffic" -- both look identical in isolation.
# The fix: require a GENUINE preceding stop (sustained near-zero speed,
# not just one low reading -- stop-and-go traffic rarely holds still
# this long) before walking is even considered, AND require the walking-
# pace pattern to be SUSTAINED across a couple of readings, not a single
# noisy blip. Exiting back to vehicle speed stays immediate -- no one
# walking suddenly moves at real vehicle speed, so that transition is
# unambiguous in a single reading and doesn't need the same caution.
WALKING_MIN_PARK_SECONDS = 15
WALKING_PATTERN_CONSECUTIVE_READINGS = 2
# Default/fallback only -- see _learned_recently_parked_window_seconds.
# Once enough real park-to-walk gaps have been observed, the learned
# value replaces this fixed guess.
WALKING_RECENTLY_PARKED_WINDOW_SECONDS = 5 * 60
PARK_TO_WALK_GAP_MIN_SAMPLES_TO_LEARN = 10

# Conservative fallback used only when no real countdown was readable
# from the offer screen (see OfferScreenParser.extract_countdown_seconds)
# -- deliberately generous so a genuinely still-pending offer doesn't get
# wrongly recovered as "timed out" too early.
DEFAULT_OFFER_TIMEOUT_ASSUMPTION_SECONDS = 90

# Per-restaurant, not global -- "difficult" at one location might be an
# entirely normal duration at another (a sprawling shopping-center car
# park vs. a tight street), so this needs its own confirmed history per
# restaurant before it means anything.
PARKING_DIFFICULTY_MIN_SAMPLES = 3

# Reuses the same ~1.1km grid already proven in zone-based traffic-risk
# learning, rather than a raw geographic average (which could suggest a
# nonsensical midpoint if real pickup locations are spread out and not
# actually clustered anywhere). Needs real history to build from -- this
# starts persisting real pickup coordinates going forward; it can't be
# backfilled from anything recorded before this existed.
PICKUP_SWEET_SPOT_GRID_DECIMALS = 2
PICKUP_SWEET_SPOT_MIN_SAMPLES = 15

# Reasonable starting default for "far enough from your usual hotspot to
# plausibly be in unfamiliar territory" -- adjustable if 2km turns out
# too sensitive or not sensitive enough in practice.
UNFAMILIAR_AREA_THRESHOLD_KM = 2.0

HARSH_ACCEL_MS2 = 2.5
HARSH_BRAKE_MS2 = -2.5
DEFAULT_SPEED_LIMIT_KMH = 60

# UNCONFIRMED placeholder, same honesty status as every other threshold
# guess in this file -- how many standard deviations from YOUR OWN mean
# per-tick acceleration counts as "harsh" for you specifically, once a
# personal mean/std can actually be computed (see
# TripManager._record_accel_sample_in_memory / _learned_accel_brake_thresholds).
ACCEL_BRAKE_STD_MULTIPLIER = 2.5
# Deliberately much higher than the 5-trip thresholds used for deadhead/
# wait-time/delivery-speed/peak-hour learning elsewhere in this file --
# those are per-TRIP aggregates; this is per-GPS-TICK samples (many per
# trip), so meaningfully estimating a personal accel/brake distribution
# needs a lot more raw data points before it's trustworthy.
ACCEL_BRAKE_MIN_SAMPLES_TO_LEARN = 300
# Floor so a driver with an unusually smooth/uniform early sample (small
# std by chance, not by true driving style) doesn't get a hypersensitive
# threshold that flags ordinary driving as harsh.
ACCEL_BRAKE_MIN_THRESHOLD_MS2 = 1.5

STOPS_BUFFER_MAX = 10
STOPS_BUFFER_TTL_SECONDS = 24 * 60 * 60

# Smart Score weights
WEIGHT_BASE_RATE = 0.36
WEIGHT_HOURLY_RATE = 0.225
WEIGHT_DEADHEAD = 0.135
WEIGHT_RESTAURANT_WAIT = 0.09
WEIGHT_TIME_OF_DAY = 0.09
WEIGHT_WEATHER = 0.10
# Original weights (before weather was added) were 0.40/0.25/0.15/0.10/0.10.
# All five scaled down by 0.9x (proportionally, so their relative importance
# to each other is unchanged) to make room for the new 10% weather factor,
# keeping the total at exactly 1.00.


# ------------------------------------------------------------------------- #
# Database
# ------------------------------------------------------------------------- #
class Database:
    def __init__(self, db_path):
        self.conn = sqlite3.connect(db_path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self._create_schema()

    def _create_schema(self):
        c = self.conn.cursor()
        c.executescript("""
        CREATE TABLE IF NOT EXISTS trips (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            start_time INTEGER,
            end_time INTEGER,
            distance_km REAL,
            moving_seconds INTEGER,
            slow_seconds INTEGER,
            stopped_seconds INTEGER,
            time_efficiency_score REAL,
            safety_score REAL,
            geofence_hit_ratio REAL,
            composite_score REAL,
            fuel_cost_estimate REAL,
            gps_points_json TEXT,
            mode TEXT DEFAULT 'GENERAL',
            start_lat REAL,
            start_lon REAL,
            offer_score_snapshot_json TEXT,
            was_interrupted INTEGER DEFAULT 0,
            pickup_arrival_ts REAL,
            pickup_departure_ts REAL,
            dropoff_arrival_ts REAL,
            walking_confirmed_ts REAL,
            deadline_text TEXT
        );

        CREATE TABLE IF NOT EXISTS stops (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            trip_id INTEGER,
            address TEXT,
            lat REAL,
            lon REAL,
            matched INTEGER DEFAULT 0,
            arrival_time INTEGER,
            earned_minutes REAL,
            unearned_minutes REAL,
            FOREIGN KEY(trip_id) REFERENCES trips(id)
        );

        CREATE TABLE IF NOT EXISTS events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            trip_id INTEGER,
            event_type TEXT,     -- harsh_accel / harsh_brake / speeding
            lat REAL,
            lon REAL,
            timestamp INTEGER,
            magnitude REAL,
            FOREIGN KEY(trip_id) REFERENCES trips(id)
        );

        CREATE TABLE IF NOT EXISTS delays (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            trip_id INTEGER,
            lat REAL,
            lon REAL,
            duration_seconds INTEGER,
            timestamp INTEGER,
            FOREIGN KEY(trip_id) REFERENCES trips(id)
        );

        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            trip_id INTEGER,
            sender TEXT,
            body TEXT,
            timestamp INTEGER,
            extracted_instruction TEXT
        );

        CREATE TABLE IF NOT EXISTS restaurant_wait_history (
            restaurant_name TEXT PRIMARY KEY,
            avg_wait_minutes REAL,
            sample_count INTEGER
        );

        CREATE TABLE IF NOT EXISTS trusted_senders (
            name TEXT PRIMARY KEY
        );

        CREATE TABLE IF NOT EXISTS canned_replies (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            text TEXT,
            sort_order INTEGER
        );

        CREATE TABLE IF NOT EXISTS parking_difficulty_feedback (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            restaurant_name TEXT,
            gap_seconds REAL,
            difficulty TEXT,
            timestamp REAL
        );

        CREATE TABLE IF NOT EXISTS pickup_location_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            restaurant_name TEXT,
            lat REAL,
            lon REAL,
            timestamp REAL
        );

        -- Per-restaurant, persistent (not per-trip) notes about the pickup
        -- location itself -- e.g. "gate code 1234", "enter through side
        -- door", "park in the loading zone, not the main lot". Keyed by
        -- restaurant_name so a note added once is still there next time an
        -- offer comes in from the same place, same pattern already used for
        -- parking_difficulty_feedback and restaurant_wait_history.
        CREATE TABLE IF NOT EXISTS pickup_location_notes (
            restaurant_name TEXT PRIMARY KEY,
            notes TEXT,
            updated_ts REAL
        );

        CREATE TABLE IF NOT EXISTS pending_offer_recovery (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            restaurant_name TEXT,
            payout REAL,
            distance_km REAL,
            smart_score REAL,
            components_json TEXT,
            detected_ts REAL,
            expires_ts REAL
        );

        CREATE TABLE IF NOT EXISTS offer_distance_accuracy (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            trip_id INTEGER,
            restaurant_name TEXT,
            claimed_distance_km REAL,
            actual_deadhead_km REAL,
            actual_delivery_km REAL,
            actual_total_km REAL,
            timestamp INTEGER
        );

        CREATE TABLE IF NOT EXISTS delivery_speed_history (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            avg_speed_kmh REAL,
            sample_count INTEGER
        );

        CREATE TABLE IF NOT EXISTS walking_speed_history (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            avg_speed_kmh REAL,
            sample_count INTEGER
        );

        -- Running Welford's-algorithm mean/variance of every real per-tick
        -- acceleration sample (m/s^2, signed -- both accel and brake are
        -- just the two tails of one distribution), not just ones that
        -- already crossed the fixed HARSH_ACCEL_MS2/HARSH_BRAKE_MS2
        -- thresholds -- learning from only-already-harsh events would be
        -- circular. mean_squared_diff is Welford's M2 accumulator, not a
        -- variance itself (divide by sample_count to get variance -- see
        -- TripManager._learned_accel_brake_thresholds).
        CREATE TABLE IF NOT EXISTS accel_dynamics_history (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            sample_count INTEGER,
            mean_accel_ms2 REAL,
            mean_squared_diff REAL
        );

        CREATE TABLE IF NOT EXISTS park_to_walk_gap_history (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            avg_gap_seconds REAL,
            sample_count INTEGER
        );

        CREATE TABLE IF NOT EXISTS diagnostic_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER,
            category TEXT,
            message TEXT
        );

        CREATE TABLE IF NOT EXISTS trip_feedback (
            trip_id INTEGER PRIMARY KEY,
            rating INTEGER,
            notes TEXT,
            parking_rating TEXT,
            navigation_rating TEXT,
            merchant_wait_rating TEXT,
            customer_rating TEXT,
            overall_rating TEXT,
            timestamp INTEGER
        );

        CREATE TABLE IF NOT EXISTS offer_outcomes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            restaurant_name TEXT,
            payout REAL,
            distance_km REAL,
            smart_score REAL,
            accepted INTEGER,
            outcome TEXT DEFAULT 'accepted',
            timestamp INTEGER,
            components_json TEXT,
            is_test_data INTEGER DEFAULT 0
        );

        CREATE TABLE IF NOT EXISTS personal_calibration (
            factor_name TEXT PRIMARY KEY,
            adjustment_pct REAL,
            correlation REAL,
            sample_count INTEGER,
            computed_at INTEGER
        );
        """)
        self.conn.commit()

        # Seeded once, only if genuinely empty -- never re-seeds after
        # the user has added/edited/deleted their own replies, so this
        # only ever runs on a truly fresh database.
        existing = self.conn.execute("SELECT COUNT(*) AS cnt FROM canned_replies").fetchone()
        if existing["cnt"] == 0:
            starter_replies = [
                "On my way now!",
                "About 5 minutes away",
                "Got it, thank you!",
                "I'm outside",
                "Sorry, running a bit late",
                "Leaving it at the door as requested",
                "Can't find the entrance, can you help?",
                "Thanks, have a great day!",
            ]
            for i, text in enumerate(starter_replies):
                self.conn.execute(
                    "INSERT INTO canned_replies (text, sort_order) VALUES (?, ?)", (text, i)
                )
            self.conn.commit()

        # Migration for databases that already existed before is_test_data
        # was added -- CREATE TABLE IF NOT EXISTS alone doesn't add a new
        # column to a table that's already there. Also retroactively flags
        # any test data ALREADY recorded via the Developer Testing "Simulate
        # Offer Outcomes" button (which always uses these exact, recognizable
        # restaurant names) -- otherwise, only FUTURE test runs would be
        # excluded from reports, and anything already recorded from earlier
        # testing would keep polluting stats forever.
        existing_columns = [row["name"] for row in self.conn.execute("PRAGMA table_info(offer_outcomes)")]
        if "is_test_data" not in existing_columns:
            self.conn.execute("ALTER TABLE offer_outcomes ADD COLUMN is_test_data INTEGER DEFAULT 0")
            self.conn.execute("""
                UPDATE offer_outcomes SET is_test_data = 1
                WHERE restaurant_name IN ('Test Accepted Place', 'Test Declined Place', 'Test Timed Out Place')
            """)
            self.conn.commit()

        # Migration for databases that already existed before phase-timing
        # tracking was added -- existing trip rows simply won't have this
        # data (nothing to backfill it from), but new trips going forward
        # will.
        trips_columns = [row["name"] for row in self.conn.execute("PRAGMA table_info(trips)")]
        for new_column in ("pickup_arrival_ts", "pickup_departure_ts", "dropoff_arrival_ts",
                           "walking_confirmed_ts", "deadline_text", "pickup_address"):
            if new_column not in trips_columns:
                col_type = "TEXT" if new_column in ("deadline_text", "pickup_address") else "REAL"
                self.conn.execute(f"ALTER TABLE trips ADD COLUMN {new_column} {col_type}")
        self.conn.commit()


# ------------------------------------------------------------------------- #
# Smart Score Engine ("Profit Advisor")
# ------------------------------------------------------------------------- #
class SmartScoreEngine:
    # Rough average delivery speed accounting for city driving + stop lights;
    # used to estimate trip duration for the $/hr calculation when no better
    # duration estimate is available. NOTE: earlier versions used "time until
    # the offer's deadline" for this, which is wrong -- that's slack time,
    # not travel time, and produces a misleading $/hr that swings wildly
    # depending on what moment you happen to open the offer.
    ASSUMED_DELIVERY_SPEED_KMH = 25.0

    # Personal calibration: bounded, evidence-gated adjustment of the base
    # weights toward your own actual satisfaction -- NOT free-form learning.
    # The base weights (WEIGHT_BASE_RATE etc.) are always the floor; this
    # can only nudge each one by at most CALIBRATION_MAX_ADJUSTMENT_PCT in
    # either direction, and only once CALIBRATION_MIN_SAMPLES rated trips
    # exist, specifically to avoid overfitting to a handful of ratings.
    CALIBRATION_MIN_SAMPLES = 25
    CALIBRATION_MAX_ADJUSTMENT_PCT = 0.15

    def __init__(self, db: Database):
        self.db = db
        self._live_traffic_ratio = None
        self._live_traffic_timestamp = None
        self._live_weather = None
        self._live_weather_timestamp = None

    def estimate_minutes_from_distance(self, distance_km):
        if not distance_km or distance_km <= 0:
            return None
        speed_kmh, _, _ = self._learned_delivery_speed_kmh()
        return max(5.0, (distance_km / speed_kmh) * 60.0)

    def _learned_delivery_speed_kmh(self):
        """
        Returns (speed_kmh, sample_count, is_learned). Same fix as
        deadhead: this used to be a fixed 25 km/h guess for every driver
        everywhere. Now it's learned from this driver's own completed
        deliveries -- measured over the exact same distance/time window
        (pickup departure -> trip end) already used for the
        offer_distance_accuracy comparison, so the two stay consistent.
        Falls back to the original 25 km/h assumption, clearly marked as
        unlearned, until at least one real delivery has completed.
        """
        row = self.db.conn.execute(
            "SELECT avg_speed_kmh, sample_count FROM delivery_speed_history WHERE id = 1"
        ).fetchone()
        if row and row["sample_count"]:
            return row["avg_speed_kmh"], row["sample_count"], True
        return self.ASSUMED_DELIVERY_SPEED_KMH, 0, False

    def record_delivery_speed(self, distance_km, time_hours):
        """
        Called once per completed delivery leg (pickup departure -> trip
        end). Learns a single global average speed (not per-restaurant --
        delivery speed reflects general traffic/road conditions for this
        driver, not something specific to any one restaurant, unlike wait
        time or deadhead).
        """
        if distance_km is None or time_hours is None or distance_km <= 0 or time_hours <= 0:
            return
        speed_kmh = distance_km / time_hours
        if speed_kmh <= 0 or speed_kmh > 150:
            return  # sanity guard against bad GPS data (e.g. a jump/glitch)
        row = self.db.conn.execute(
            "SELECT avg_speed_kmh, sample_count FROM delivery_speed_history WHERE id = 1"
        ).fetchone()
        if row and row["sample_count"]:
            new_count = row["sample_count"] + 1
            new_avg = ((row["avg_speed_kmh"] * row["sample_count"]) + speed_kmh) / new_count
        else:
            new_avg, new_count = speed_kmh, 1
        self.db.conn.execute("""
            INSERT INTO delivery_speed_history (id, avg_speed_kmh, sample_count)
            VALUES (1, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                avg_speed_kmh = excluded.avg_speed_kmh,
                sample_count = excluded.sample_count
        """, (new_avg, new_count))
        self.db.conn.commit()

    def _restaurant_wait_info(self, restaurant_name):
        """
        Returns (avg_minutes, sample_count, is_restaurant_specific).
        Three-tier fallback, same pattern as deadhead: restaurant-specific
        average if this restaurant has history, else the overall average
        across all restaurants (a smarter cold-start guess for a
        brand-new restaurant than a fixed constant), else a hardcoded
        6.0-minute starting estimate if there's no history anywhere yet.
        record_restaurant_wait() is what actually builds this history.
        """
        row = self.db.conn.execute(
            "SELECT avg_wait_minutes, sample_count FROM restaurant_wait_history "
            "WHERE restaurant_name = ?",
            (restaurant_name,),
        ).fetchone()
        if row:
            return row["avg_wait_minutes"], row["sample_count"], True

        overall = self.db.conn.execute(
            "SELECT AVG(avg_wait_minutes) AS avg_minutes, SUM(sample_count) AS total_samples "
            "FROM restaurant_wait_history"
        ).fetchone()
        if overall and overall["total_samples"]:
            return overall["avg_minutes"], overall["total_samples"], False

        return 6.0, 0, False

    def record_restaurant_wait(self, restaurant_name, wait_minutes):
        """
        Called once per pickup after TripManager detects the driver has left
        the restaurant's vicinity (see TripManager's pickup tracking). This
        is what makes restaurant wait time real, learned data instead of a
        hardcoded constant that never changes.

        Important caveat: GPS can't distinguish "time spent parking" from
        "time spent waiting for the order to be ready" -- both look
        identical (stationary near the restaurant). So this measures
        combined pickup friction time (parking + wait), not restaurant wait
        in isolation. There's no separate "parking" metric for the same
        reason -- see parking_note in calculate()'s output.
        """
        if not restaurant_name or wait_minutes is None or wait_minutes < 0:
            return
        avg, count, learned = self._restaurant_wait_info(restaurant_name)
        if learned:
            new_count = count + 1
            new_avg = ((avg * count) + wait_minutes) / new_count
        else:
            new_avg, new_count = wait_minutes, 1
        self.db.conn.execute("""
            INSERT INTO restaurant_wait_history (restaurant_name, avg_wait_minutes, sample_count)
            VALUES (?, ?, ?)
            ON CONFLICT(restaurant_name) DO UPDATE SET
                avg_wait_minutes = excluded.avg_wait_minutes,
                sample_count = excluded.sample_count
        """, (restaurant_name, new_avg, new_count))
        self.db.conn.commit()

    def _is_peak_hour(self, hour_24):
        """
        Learns YOUR actual average driving speed by hour-of-day from
        completed trips, and flags hours where you've historically driven
        notably slower as "high risk" -- a personalized signal instead of
        assuming a generic lunch/dinner rush applies everywhere. This is
        still a proxy, not live traffic data (would need an internet-
        connected maps/traffic API, which conflicts with this app's
        offline-first, no-cloud design) -- but it's now based on your own
        real driving instead of an arbitrary universal assumption.

        Returns (is_high_risk, is_personalized, sample_count). Falls back
        to the original generic lunch/dinner window guess (is_personalized
        =False) until there's enough real historical data (at least 5
        completed trips with real distance/time) to learn from, or if this
        specific hour has no data of its own yet.
        """
        rows = self.db.conn.execute("""
            SELECT start_time, distance_km, moving_seconds FROM trips
            WHERE end_time IS NOT NULL AND distance_km > 0 AND moving_seconds > 0
        """).fetchall()

        generic_guess = (11 <= hour_24 < 14) or (17 <= hour_24 < 20)
        if len(rows) < 5:
            return generic_guess, False, len(rows)

        hour_speeds = {}
        all_speeds = []
        for row in rows:
            trip_hour = datetime.fromtimestamp(row["start_time"]).hour
            speed_kmh = row["distance_km"] / (row["moving_seconds"] / 3600.0)
            hour_speeds.setdefault(trip_hour, []).append(speed_kmh)
            all_speeds.append(speed_kmh)

        if hour_24 not in hour_speeds:
            return generic_guess, False, len(rows)

        overall_avg = sum(all_speeds) / len(all_speeds)
        this_hour_avg = sum(hour_speeds[hour_24]) / len(hour_speeds[hour_24])
        is_high_risk = this_hour_avg < overall_avg * 0.85  # notably slower than your own average
        return is_high_risk, True, len(hour_speeds[hour_24])

    # Live traffic (real, via Google's Distance Matrix -- see GoogleApiHelper) -----
    LIVE_TRAFFIC_FRESHNESS_SECONDS = 5 * 60
    LIVE_TRAFFIC_HIGH_RISK_RATIO = 1.15  # 15%+ slower than typical = high risk

    def record_live_traffic_delay(self, delay_ratio):
        """
        Called from GoogleApiHelper's async Distance Matrix result (real
        current-location -> pickup traffic, via your Google Maps API key).
        Stored in memory only (not persisted) -- live traffic is
        inherently a right-now snapshot, not something meaningful to keep
        after the fact.
        """
        self._live_traffic_ratio = delay_ratio
        self._live_traffic_timestamp = time.time()

    ZONE_GRID_DECIMALS = 2  # ~1.1km grid at this latitude -- coarse "zone", not exact street
    ZONE_MIN_SAMPLES = 3    # need at least this many same-zone-same-hour trips to trust it

    def _get_traffic_risk_by_zone(self, lat, lon, hour_24):
        """
        Learns average speed for a specific (rounded) location zone AND
        hour combined -- e.g. "this zone at 5pm" vs. just "5pm anywhere" --
        more specific than the hour-only personal-history proxy. Requires
        real starting coordinates (only available once trips have been
        recorded with a real GPS fix, which they always are in practice --
        this just can't be used before ANY trips exist yet).

        Returns (is_high_risk, sample_count) or (None, 0) if there isn't
        enough same-zone-same-hour data (see ZONE_MIN_SAMPLES) to trust a
        zone-specific answer over the coarser hour-only fallback.
        """
        if lat is None or lon is None:
            return None, 0

        zone_lat = round(lat, self.ZONE_GRID_DECIMALS)
        zone_lon = round(lon, self.ZONE_GRID_DECIMALS)

        rows = self.db.conn.execute("""
            SELECT start_time, distance_km, moving_seconds, start_lat, start_lon
            FROM trips
            WHERE end_time IS NOT NULL AND distance_km > 0 AND moving_seconds > 0
                AND start_lat IS NOT NULL AND start_lon IS NOT NULL
        """).fetchall()

        zone_hour_speeds = []
        all_zone_speeds = []
        for row in rows:
            if round(row["start_lat"], self.ZONE_GRID_DECIMALS) != zone_lat:
                continue
            if round(row["start_lon"], self.ZONE_GRID_DECIMALS) != zone_lon:
                continue
            speed_kmh = row["distance_km"] / (row["moving_seconds"] / 3600.0)
            all_zone_speeds.append(speed_kmh)
            if datetime.fromtimestamp(row["start_time"]).hour == hour_24:
                zone_hour_speeds.append(speed_kmh)

        if len(zone_hour_speeds) < self.ZONE_MIN_SAMPLES or not all_zone_speeds:
            return None, 0

        zone_overall_avg = sum(all_zone_speeds) / len(all_zone_speeds)
        zone_hour_avg = sum(zone_hour_speeds) / len(zone_hour_speeds)
        is_high_risk = zone_hour_avg < zone_overall_avg * 0.85
        return is_high_risk, len(zone_hour_speeds)

    def _get_traffic_risk(self, hour_24, lat=None, lon=None):
        """
        Prefers REAL live traffic data (from your Google Maps API key) if
        a result was recorded within the last 5 minutes; then zone-based
        learning (this specific area at this hour, if enough data exists);
        then the personal-history proxy (_is_peak_hour, hour-of-day only);
        then the generic lunch/dinner guess.

        Returns (is_high_risk, source) where source is "live", "zone",
        "personal", or "generic".
        """
        live_ratio = getattr(self, "_live_traffic_ratio", None)
        live_ts = getattr(self, "_live_traffic_timestamp", None)
        if live_ratio is not None and live_ts is not None:
            if time.time() - live_ts <= self.LIVE_TRAFFIC_FRESHNESS_SECONDS:
                return live_ratio >= self.LIVE_TRAFFIC_HIGH_RISK_RATIO, "live"

        zone_risk, zone_samples = self._get_traffic_risk_by_zone(lat, lon, hour_24)
        if zone_risk is not None:
            return zone_risk, "zone"

        is_high_risk, is_personalized, _ = self._is_peak_hour(hour_24)
        return is_high_risk, ("personal" if is_personalized else "generic")

    # Live weather (real, via Open-Meteo's BOM-model wrapper -- see WeatherHelper) ---
    LIVE_WEATHER_FRESHNESS_SECONDS = 15 * 60

    def record_live_weather(self, precipitation_mm, wind_speed_kmh, temperature_c):
        """
        Called from WeatherHelper's async result (real current conditions
        for your current location, via Open-Meteo's BOM ACCESS-G model
        wrapper -- falling back to Open-Meteo's default model if BOM's
        specific data is temporarily unavailable). Stored in memory only,
        same as live traffic -- weather right now isn't meaningful to keep
        after the fact.
        """
        self._live_weather = {
            "precipitation_mm": precipitation_mm,
            "wind_speed_kmh": wind_speed_kmh,
            "temperature_c": temperature_c,
        }
        self._live_weather_timestamp = time.time()

    def _get_weather_score(self):
        """
        Returns (score, is_live, precipitation_mm, wind_speed_kmh). If no
        fresh (<15 min) live weather data exists -- no API configured,
        no current GPS fix yet, or the query failed -- returns a neutral
        100 (assume fine conditions) rather than penalizing an offer for
        data we don't actually have.

        This is a simple, honestly-labeled HEURISTIC, not a precise
        safety model: heavy rain and high wind reduce the score; nothing
        else (fog, road surface, temperature extremes) is currently
        factored in.
        """
        live_ts = getattr(self, "_live_weather_timestamp", None)
        live = getattr(self, "_live_weather", None)
        if live is None or live_ts is None:
            return 100.0, False, None, None
        if time.time() - live_ts > self.LIVE_WEATHER_FRESHNESS_SECONDS:
            return 100.0, False, None, None

        precipitation_mm = live["precipitation_mm"]
        wind_speed_kmh = live["wind_speed_kmh"]
        score = 100.0
        if precipitation_mm is not None:
            score -= min(60.0, precipitation_mm * 15.0)
        if wind_speed_kmh is not None and wind_speed_kmh > 30:
            score -= min(30.0, (wind_speed_kmh - 30) * 1.5)
        return max(0.0, score), True, precipitation_mm, wind_speed_kmh

    def _estimate_deadhead_km(self, restaurant_name):
        """
        Returns (estimated_km, sample_count, is_restaurant_specific).

        Previously this factor was always fed a hardcoded 0.0 (the offer
        screen never shows deadhead separately), which silently maxed the
        deadhead_score at 100 for every single offer -- a real bug, not a
        judgment call. Now it uses this driver's own historical actual
        deadhead measurements (from TripManager's pickup-distance tracking,
        recorded into offer_distance_accuracy once a delivery completes):
        restaurant-specific average if this restaurant has history, else
        the overall average across all restaurants, else 0.0 with zero
        samples if there's no history at all yet (first few deliveries).
        """
        row = self.db.conn.execute("""
            SELECT AVG(actual_deadhead_km) AS avg_km, COUNT(*) AS cnt
            FROM offer_distance_accuracy WHERE restaurant_name = ?
        """, (restaurant_name,)).fetchone()
        if row and row["cnt"] and row["avg_km"] is not None:
            return row["avg_km"], row["cnt"], True

        overall = self.db.conn.execute("""
            SELECT AVG(actual_deadhead_km) AS avg_km, COUNT(*) AS cnt
            FROM offer_distance_accuracy
        """).fetchone()
        if overall and overall["cnt"] and overall["avg_km"] is not None:
            return overall["avg_km"], overall["cnt"], False

        return 0.0, 0, False

    def calculate(self, payout, distance_km, est_minutes, restaurant_name, hour_24,
                  current_lat=None, current_lon=None):
        # Base $/km  (assume $2/km == 100)
        base_rate = payout / distance_km if distance_km > 0 else 0
        base_score = min(100.0, (base_rate / 2.0) * 100.0)

        # Hourly rate (assume $30/hr baseline == ~50, $60/hr == 100)
        hourly_rate = (payout / est_minutes) * 60 if est_minutes > 0 else 0
        hourly_score = min(100.0, (hourly_rate / 60.0) * 100.0)

        # Deadhead penalty (0km => 100, scales down with distance) --
        # now a real learned estimate, not a hardcoded 0.
        deadhead_km, deadhead_samples, deadhead_is_restaurant_specific = (
            self._estimate_deadhead_km(restaurant_name)
        )
        deadhead_score = max(0.0, 100.0 - (deadhead_km * 10.0))

        # Restaurant wait / pickup friction (avg 3 min == 100, 15+ min == 0)
        # Confirmed real bug, fixed here: unlike base_score/hourly_score
        # just above (both min(100.0, ...)-clamped) or deadhead_score/
        # weather_score (bounded 0-100 by construction), this had no upper
        # clamp -- an average wait under 3 minutes (a fast, real, common
        # pickup) pushed wait_score, and therefore final_score, above the
        # documented 0-100 scale (e.g. avg_wait=0 -> 124).
        avg_wait, wait_samples, wait_is_restaurant_specific = self._restaurant_wait_info(restaurant_name)
        wait_score = max(0.0, min(100.0, 100.0 - ((avg_wait - 3.0) * 8.0)))

        # Time of day / traffic (prefers real live traffic if a Google Maps
        # API key is configured and a fresh result exists -- see
        # _get_traffic_risk -- else falls back to the personalized
        # historical-speed proxy, else the generic lunch/dinner guess)
        is_high_risk, traffic_risk_source = self._get_traffic_risk(hour_24, current_lat, current_lon)
        time_score = 100.0 if is_high_risk else 70.0

        # Weather (real, via Open-Meteo -- see WeatherHelper.java). Simple
        # heuristic: heavy rain / high wind reduce the score. Neutral 100
        # (assume fine conditions) if no fresh live reading exists yet.
        weather_score, weather_is_live, weather_precip_mm, weather_wind_kmh = (
            self._get_weather_score()
        )

        # Exposed for transparency only -- est_minutes (used above for
        # hourly_rate) was already computed via estimate_minutes_from_distance()
        # before this call; this just surfaces whether that used a learned
        # speed or the original 25 km/h assumption.
        delivery_speed_kmh, delivery_speed_samples, delivery_speed_is_learned = (
            self._learned_delivery_speed_kmh()
        )

        # Personal calibration: bounded, evidence-gated nudge toward your
        # own actual satisfaction (see recalculate_personal_calibration).
        # Falls back to the exact base weights if no calibration has been
        # computed yet, or not enough rated trips exist -- this call is
        # cheap (a handful of PRIMARY KEY lookups) and safe to make every
        # time a score is calculated.
        weights = self._get_calibrated_weights()

        final_score = (
            base_score * weights["base_rate"]
            + hourly_score * weights["hourly_rate"]
            + deadhead_score * weights["deadhead"]
            + wait_score * weights["restaurant_wait"]
            + time_score * weights["time_of_day"]
            + weather_score * weights["weather"]
        )

        traffic_risk_labels = {
            "live": "High (live traffic)" if is_high_risk else "Low (live traffic)",
            "zone": "High (this area, this hour)" if is_high_risk else "Low (this area, this hour)",
            "personal": "High (your usual pace here)" if is_high_risk else "Low (your usual pace here)",
            "generic": "High (peak hours, generic)" if is_high_risk else "Low (off-peak, generic)",
        }

        if weather_is_live:
            weather_label = f"{weather_precip_mm:.1f}mm rain, {weather_wind_kmh:.0f}km/h wind"
        else:
            weather_label = "unknown (assumed fine)"

        # One-sentence synthesis of the six factors -- the take-away
        # already assembled instead of needing to mentally combine six
        # separate numbers yourself while deciding whether to accept.
        verdict_sentence = self._synthesize_verdict(
            base_rate, deadhead_km, deadhead_is_restaurant_specific,
            avg_wait, wait_is_restaurant_specific, traffic_risk_labels[traffic_risk_source],
            weather_is_live, weather_precip_mm,
        )

        # Proactive warning if THIS specific restaurant has a notably bad
        # learned history -- surfaced directly rather than something you'd
        # have to remember to check in the Address Book yourself.
        restaurant_warning = None
        if wait_is_restaurant_specific and avg_wait > 12:
            restaurant_warning = f"Long wait here historically (avg {avg_wait:.0f} min)"
        elif deadhead_is_restaurant_specific and deadhead_km > 5:
            restaurant_warning = f"Long deadhead to this restaurant historically (avg {deadhead_km:.1f} km)"

        return {
            "final_score": round(final_score, 1),
            "label": self._label(final_score),
            "verdict_sentence": verdict_sentence,
            "restaurant_warning": restaurant_warning,
            "base_rate_per_km": round(base_rate, 2),
            "hourly_rate": round(hourly_rate, 2),
            "deadhead_km": round(deadhead_km, 2),
            "deadhead_samples": deadhead_samples,
            "deadhead_is_restaurant_specific": deadhead_is_restaurant_specific,
            "restaurant_wait_minutes": round(avg_wait, 1),
            "restaurant_wait_samples": wait_samples,
            "restaurant_wait_is_learned": wait_samples > 0,
            "restaurant_wait_is_restaurant_specific": wait_is_restaurant_specific,
            "delivery_speed_kmh": round(delivery_speed_kmh, 1),
            "delivery_speed_samples": delivery_speed_samples,
            "delivery_speed_is_learned": delivery_speed_is_learned,
            "traffic_risk": traffic_risk_labels[traffic_risk_source],
            "traffic_risk_source": traffic_risk_source,
            "weather": weather_label,
            "weather_is_live": weather_is_live,
            "parking_note": "Not tracked separately -- folded into pickup wait time",
            "components": {
                "base_score": round(base_score, 1),
                "hourly_score": round(hourly_score, 1),
                "deadhead_score": round(deadhead_score, 1),
                "wait_score": round(wait_score, 1),
                "time_score": round(time_score, 1),
                "weather_score": round(weather_score, 1),
            },
        }

    def _synthesize_verdict(self, base_rate, deadhead_km, deadhead_is_restaurant_specific,
                             avg_wait, wait_is_restaurant_specific, traffic_risk_label,
                             weather_is_live, weather_precip_mm):
        """
        Turns the six-factor breakdown into one plain-English sentence --
        the whole point is that the take-away is already assembled for
        you, instead of needing to mentally combine six separate numbers
        while you're deciding whether to accept. Full numeric breakdown
        is still available afterward (see get_trip_summary_by_id) once
        there's no time pressure to actually read it.
        """
        parts = []
        if base_rate >= 2.0:
            parts.append("good pay")
        elif base_rate >= 1.2:
            parts.append("okay pay")
        else:
            parts.append("low pay")

        if deadhead_is_restaurant_specific and deadhead_km > 3:
            parts.append(f"a {deadhead_km:.1f}km drive just to reach the pickup")

        if wait_is_restaurant_specific and avg_wait > 10:
            parts.append(f"a historically slow pickup here (~{avg_wait:.0f} min)")

        if "High" in traffic_risk_label:
            parts.append("heavier traffic than usual for this time/area")

        if weather_is_live and weather_precip_mm is not None and weather_precip_mm > 1.0:
            parts.append("rain expected")

        if len(parts) == 1:
            return parts[0][0].upper() + parts[0][1:] + "."
        return parts[0][0].upper() + parts[0][1:] + ", but " + " and ".join(parts[1:]) + "."

    def _get_calibrated_weights(self):
        """
        Returns the 6 weights actually used for scoring -- the fixed base
        weights, each optionally nudged by a bounded personal-calibration
        adjustment (see recalculate_personal_calibration). Renormalizes
        so the 6 weights still sum to 1.0 after adjustment, keeping the
        final score on the same 0-100 scale it's always been on.
        """
        base_weights = {
            "base_rate": WEIGHT_BASE_RATE, "hourly_rate": WEIGHT_HOURLY_RATE,
            "deadhead": WEIGHT_DEADHEAD, "restaurant_wait": WEIGHT_RESTAURANT_WAIT,
            "time_of_day": WEIGHT_TIME_OF_DAY, "weather": WEIGHT_WEATHER,
        }
        rows = self.db.conn.execute(
            "SELECT factor_name, adjustment_pct FROM personal_calibration"
        ).fetchall()
        if not rows:
            return base_weights

        adjusted = dict(base_weights)
        for row in rows:
            factor = row["factor_name"]
            if factor in adjusted:
                adjusted[factor] = max(0.0, adjusted[factor] * (1.0 + row["adjustment_pct"]))

        total = sum(adjusted.values())
        if total <= 0:
            return base_weights
        return {k: v / total for k, v in adjusted.items()}

    @staticmethod
    def _pearson_correlation(xs, ys):
        """Plain-Python Pearson correlation coefficient, -1 to 1. No numpy dependency needed for this."""
        n = len(xs)
        if n < 2:
            return 0.0
        mean_x = sum(xs) / n
        mean_y = sum(ys) / n
        cov = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys))
        var_x = sum((x - mean_x) ** 2 for x in xs)
        var_y = sum((y - mean_y) ** 2 for y in ys)
        if var_x <= 0 or var_y <= 0:
            return 0.0
        return cov / ((var_x ** 0.5) * (var_y ** 0.5))

    def recalculate_personal_calibration(self):
        """
        The actual "learn from history" step: for each of the 6 scoring
        factors, correlates that factor's sub-score against your ACTUAL
        satisfaction -- from TWO combined sources, not just one:

        1. Completed, rated trips (overall_rating from the 5-category
           feedback, or the plain 1-5 rating as a fallback).
        2. Accept/decline decisions themselves (accepted=100, declined=0)
           -- a decline is a real signal on its own: whatever factor made
           the score look good but didn't look good enough to actually
           accept is exactly the kind of mismatch this is meant to catch.
           Timeouts are deliberately excluded (see record_offer_timeout --
           not a real preference signal the way an active decline is).

        Gated on CALIBRATION_MIN_SAMPLES combined across both sources --
        with fewer samples than that, this clears any existing
        calibration and returns early rather than computing anything from
        too little evidence to trust.

        Each factor's adjustment is the correlation coefficient scaled to
        CALIBRATION_MAX_ADJUSTMENT_PCT -- a factor that correlates
        perfectly (1.0) with your satisfaction gets nudged up by the full
        15%; one that correlates with LOWER satisfaction when it scores
        well (negative correlation) gets nudged down; near-zero
        correlation leaves it essentially unchanged. This never replaces
        the base weights, only bends them within a bounded range.
        """
        overall_rating_map = {"Good": 100.0, "Okay": 50.0, "Bad": 0.0}

        factor_keys = ["base_score", "hourly_score", "deadhead_score",
                       "wait_score", "time_score", "weather_score"]
        factor_to_weight_name = {
            "base_score": "base_rate", "hourly_score": "hourly_rate",
            "deadhead_score": "deadhead", "wait_score": "restaurant_wait",
            "time_score": "time_of_day", "weather_score": "weather",
        }
        samples = {k: {"factor": [], "satisfaction": []} for k in factor_keys}

        # Source 1: completed, rated trips.
        trip_rows = self.db.conn.execute("""
            SELECT t.offer_score_snapshot_json, tf.overall_rating, tf.rating
            FROM trips t JOIN trip_feedback tf ON tf.trip_id = t.id
            WHERE t.offer_score_snapshot_json IS NOT NULL
        """).fetchall()
        for row in trip_rows:
            try:
                snapshot = json.loads(row["offer_score_snapshot_json"])
            except (ValueError, TypeError):
                continue
            components = snapshot.get("components")
            if not components:
                continue
            if row["overall_rating"] in overall_rating_map:
                satisfaction = overall_rating_map[row["overall_rating"]]
            elif row["rating"] is not None:
                satisfaction = (row["rating"] - 1) / 4.0 * 100.0
            else:
                continue
            for key in factor_keys:
                if key in components:
                    samples[key]["factor"].append(components[key])
                    samples[key]["satisfaction"].append(satisfaction)

        # Source 2: accept/decline decisions themselves (timeouts excluded).
        outcome_rows = self.db.conn.execute("""
            SELECT outcome, components_json FROM offer_outcomes
            WHERE components_json IS NOT NULL AND outcome IN ('accepted', 'declined') AND is_test_data = 0
        """).fetchall()
        for row in outcome_rows:
            try:
                components = json.loads(row["components_json"])
            except (ValueError, TypeError):
                continue
            satisfaction = 100.0 if row["outcome"] == "accepted" else 0.0
            for key in factor_keys:
                if key in components:
                    samples[key]["factor"].append(components[key])
                    samples[key]["satisfaction"].append(satisfaction)

        sample_count = max((len(v["factor"]) for v in samples.values()), default=0)

        self.db.conn.execute("DELETE FROM personal_calibration")
        if sample_count < self.CALIBRATION_MIN_SAMPLES:
            self.db.conn.commit()
            return json.dumps({
                "applied": False,
                "sample_count": sample_count,
                "min_required": self.CALIBRATION_MIN_SAMPLES,
            })

        now = time.time()
        results = []
        for key in factor_keys:
            xs, ys = samples[key]["factor"], samples[key]["satisfaction"]
            correlation = self._pearson_correlation(xs, ys)
            adjustment_pct = correlation * self.CALIBRATION_MAX_ADJUSTMENT_PCT
            weight_name = factor_to_weight_name[key]
            self.db.conn.execute("""
                INSERT INTO personal_calibration (factor_name, adjustment_pct, correlation, sample_count, computed_at)
                VALUES (?, ?, ?, ?, ?)
            """, (weight_name, adjustment_pct, correlation, len(xs), now))
            results.append({
                "factor": weight_name, "correlation": round(correlation, 2),
                "adjustment_pct": round(adjustment_pct * 100, 1), "sample_count": len(xs),
            })
        self.db.conn.commit()
        return json.dumps({"applied": True, "sample_count": sample_count, "factors": results})

    def get_personal_calibration_summary(self):
        """Current calibration state, for transparency -- shown in-app rather than being a silent black box."""
        rows = self.db.conn.execute(
            "SELECT factor_name, adjustment_pct, correlation, sample_count, computed_at FROM personal_calibration"
        ).fetchall()
        if not rows:
            return json.dumps({"active": False})
        factors = [{
            "factor": r["factor_name"], "adjustment_pct": round(r["adjustment_pct"] * 100, 1),
            "correlation": round(r["correlation"], 2), "sample_count": r["sample_count"],
        } for r in rows]
        return json.dumps({"active": True, "factors": factors})

    def reset_personal_calibration(self):
        """Clears all calibration, reverting to the exact base weights -- in case it ever drifts somewhere you disagree with."""
        self.db.conn.execute("DELETE FROM personal_calibration")
        self.db.conn.commit()

    @staticmethod
    def _label(score):
        if score >= 85:
            return "Excellent"
        if score >= 70:
            return "Good"
        if score >= 50:
            return "Fair"
        return "Poor"


# ------------------------------------------------------------------------- #
# Geo helpers
# ------------------------------------------------------------------------- #
def haversine_meters(lat1, lon1, lat2, lon2):
    R = 6371000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = (math.sin(dphi / 2) ** 2
         + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2)
    return 2 * R * math.asin(math.sqrt(a))


# ------------------------------------------------------------------------- #
# Message Intelligence
# ------------------------------------------------------------------------- #
class MessageIntelligence:
    """Very small rule-based parser for customer messages (report section 4)."""

    INSTRUCTION_KEYWORDS = ["leave at", "back door", "gate code", "door code",
                             "buzz", "apartment", "unit", "leave it"]
    ADDRESS_CORRECTION_KEYWORDS = ["not ", "actually ", "it's ", "its "]
    LATE_KEYWORDS = ["running late", "be there", "few more minutes", "on my way"]

    @classmethod
    def classify(cls, package_name, is_messaging_style):
        """Only process Dasher-app or SMS customer messages (MessagingStyle)."""
        allowed_packages = ("com.doordash.driverapp", "com.google.android.apps.messaging")
        return is_messaging_style and package_name in allowed_packages

    @classmethod
    def extract_instruction(cls, body: str):
        lower = body.lower()
        for kw in cls.INSTRUCTION_KEYWORDS:
            if kw in lower:
                return f"delivery_note: {body.strip()}"
        for kw in cls.ADDRESS_CORRECTION_KEYWORDS:
            if kw in lower:
                return f"address_correction: {body.strip()}"
        for kw in cls.LATE_KEYWORDS:
            if kw in lower:
                return f"eta_adjustment: {body.strip()}"
        return None

    @classmethod
    def is_urgent(cls, instruction):
        """
        Triage: delivery_note (leave at door, gate code, etc.) and
        address_correction both affect where you're going or what to do
        right now -- read immediately. eta_adjustment ("running late",
        "5 more minutes") is lower-priority -- doesn't need to interrupt
        immediately, can be batched with other low-priority updates
        instead of each one interrupting individually.
        """
        if not instruction:
            return False
        return instruction.startswith("delivery_note:") or instruction.startswith("address_correction:")


# ------------------------------------------------------------------------- #
# Trusted Contacts (personal-message allowlist)
# ------------------------------------------------------------------------- #
# Separate from, and independent of, MessageIntelligence above -- that class
# handles WORK messages (Dasher app / customer SMS) by keyword. This class
# handles PERSONAL messages (SMS / Facebook Messenger) by sender identity:
# only senders the user has explicitly added get read aloud. Everyone else
# is silently ignored, including saved contacts the user hasn't added --
# this is an allowlist, not a block-list, by design (privacy-first: no
# message content from anyone not explicitly trusted is ever surfaced).
class TrustedContacts:
    def __init__(self, db: Database):
        self.db = db

    @staticmethod
    def _normalize(name):
        return (name or "").strip().lower()

    def add(self, name):
        normalized = self._normalize(name)
        if not normalized:
            return
        self.db.conn.execute(
            "INSERT OR IGNORE INTO trusted_senders (name) VALUES (?)", (normalized,)
        )
        self.db.conn.commit()

    def remove(self, name):
        normalized = self._normalize(name)
        self.db.conn.execute(
            "DELETE FROM trusted_senders WHERE name = ?", (normalized,)
        )
        self.db.conn.commit()

    def is_trusted(self, sender_name):
        """
        Substring match, not exact match: a trusted entry like "mom" or
        "maria" matches "Mom", "Mom ❤️", "Maria Garcia", "Maria G. 💜", etc.
        This matters because the SAME PERSON often shows up under different
        display names across apps -- SMS typically uses the name saved in
        your phone contacts, while Messenger uses their Facebook profile
        name, which can be completely different (nickname, maiden name,
        decorative emoji). One short, distinctive entry (e.g. a first name)
        usually covers a person across every app, instead of needing a
        separate exact entry per app per name variant.

        Tradeoff: a very short or common entry (e.g. "sam") could match an
        unrelated sender whose name happens to contain it (e.g. "Samantha").
        Use a longer or more specific fragment if that's a concern.
        """
        normalized_sender = self._normalize(sender_name)
        if not normalized_sender:
            return False
        all_trusted = self.list_all()
        if not all_trusted:
            # No contacts added yet at all -- read everything by default
            # rather than silently reading nothing with no indication why.
            # Once at least one contact is added, this reverts to a real
            # allowlist (only matching entries get read) -- this default
            # only applies to a genuinely empty list, not a short list.
            return True
        for trusted_entry in all_trusted:
            if trusted_entry and trusted_entry in normalized_sender:
                return True
        return False

    def list_all(self):
        rows = self.db.conn.execute(
            "SELECT name FROM trusted_senders ORDER BY name"
        ).fetchall()
        return [r["name"] for r in rows]


# ------------------------------------------------------------------------- #
# Offer Screen Parser
# ------------------------------------------------------------------------- #
# Learned directly from a real Dasher offer screenshot. The offer sheet is
# rendered INSIDE the app (map + bottom sheet), not as a system notification,
# so this data has to come from the Accessibility Service reading on-screen
# text nodes while the offer screen is showing -- not from
# NotificationListenerService as originally sketched.
#
# Observed layout (top-to-bottom text nodes on the offer bottom sheet):
#   "$13.65"                  <- payout
#   "Guaranteed"
#   "5.1 km"                  <- total distance
#   "Deliver by 4:25 pm"      <- delivery deadline (used to estimate minutes)
#   "Pickup"                  <- label
#   "KFC Fairy Meadow"        <- restaurant name (line right after "Pickup")
#   "Customer drop-off"       <- generic placeholder; real address hidden
#                                 until after Accept is tapped
#   "Accept"
#   "17"                      <- seconds left on the accept/decline countdown
#
# NOTE: this screen does not show a separate "distance to pickup" (deadhead)
# figure, only the total trip distance -- so deadhead_km defaults to 0.0
# until/unless a screen variant that shows it is captured.
class OfferScreenParser:
    PAYOUT_RE = re.compile(r"\$\s*(\d+\.\d{2})")
    DISTANCE_RE = re.compile(r"(\d+(?:\.\d+)?)\s*(km|mi)\b", re.IGNORECASE)
    DEADLINE_RE = re.compile(r"Deliver by\s+(\d{1,2}:\d{2}\s*[ap]m)", re.IGNORECASE)
    BARE_NUMBER_RE = re.compile(r"^\d{1,2}$")
    # Independent patterns -- previously one combined regex REQUIRED the
    # word "items" to match anything at all, so a real batch offer showing
    # only "(2 orders)" with no item count (confirmed via a real Red
    # Rooster screenshot) silently extracted nothing. Each now matches
    # independently of whether the other is present.
    ORDER_COUNT_RE = re.compile(r"\((\d+)\s+orders?\b", re.IGNORECASE)
    ITEM_COUNT_RE = re.compile(r"(\d+)\s+items?\)", re.IGNORECASE)
    ORDER_SIZE_RE = re.compile(r"Contains\s+\d+.*?order\(s\)", re.IGNORECASE)
    DROP_OFF_LABEL_RE = re.compile(r"^customer\s+drop-?off$", re.IGNORECASE)
    # Learned from a real "retail pickup" (grocery) offer screenshot -- these
    # banners warn the driver about extra steps required at pickup.
    SPECIAL_INSTRUCTION_KEYWORDS = [
        "check-in is required", "scan barcode", "qr code", "verification code",
        "pin code", "id required", "age verification",
    ]

    @classmethod
    def parse(cls, lines):
        """lines: ordered list[str] of on-screen text nodes (top-to-bottom)."""
        joined = "\n".join(lines)
        result = {
            "payout": None,
            "distance_km": None,
            "deadline_text": None,
            "restaurant_name": None,
            "item_count": None,
            "order_count": None,
            "drop_off_count": None,
            "special_instructions": None,
            "order_size_note": None,
            "countdown_seconds": None,
        }

        m = cls.PAYOUT_RE.search(joined)
        if m:
            result["payout"] = float(m.group(1))

        m = cls.DISTANCE_RE.search(joined)
        if m:
            value, unit = float(m.group(1)), m.group(2).lower()
            result["distance_km"] = value if unit == "km" else round(value * 1.60934, 2)

        m = cls.DEADLINE_RE.search(joined)
        if m:
            result["deadline_text"] = m.group(1)

        # Real "Customer drop-off" appears once per stop -- built from a
        # real batch-order screenshot showing two consecutive occurrences
        # for a 2-stop batch. This counts them regardless of position,
        # since a batch offer's drop-offs are listed together as separate
        # lines, not nested inside one combined line.
        result["drop_off_count"] = sum(
            1 for line in lines if cls.DROP_OFF_LABEL_RE.match(line.strip())
        ) or None

        # Store/restaurant name = the line right after a pickup-type label.
        # Covers both "Pickup" (restaurant) and "Retail pickup" (grocery) --
        # any line simply ending in "pickup" is treated as that label.
        for i, line in enumerate(lines):
            if line.strip().lower().endswith("pickup") and i + 1 < len(lines):
                store_line = lines[i + 1].strip()
                # Some layouts put "(N items)"/"(N orders, M items)"/
                # "(N orders)" on the same line as the name; others put it
                # on its own following line -- handle both, and handle
                # order-count and item-count independently since a real
                # offer might show only one of the two.
                order_match = cls.ORDER_COUNT_RE.search(store_line)
                item_match = cls.ITEM_COUNT_RE.search(store_line)
                if order_match or item_match:
                    result["order_count"] = int(order_match.group(1)) if order_match else None
                    result["item_count"] = int(item_match.group(1)) if item_match else None
                    store_line = re.sub(r"\(.*?\)", "", store_line).strip()
                result["restaurant_name"] = store_line
                if result["order_count"] is None and result["item_count"] is None and i + 2 < len(lines):
                    next_line = lines[i + 2]
                    order_match2 = cls.ORDER_COUNT_RE.search(next_line)
                    item_match2 = cls.ITEM_COUNT_RE.search(next_line)
                    if order_match2 or item_match2:
                        result["order_count"] = int(order_match2.group(1)) if order_match2 else None
                        result["item_count"] = int(item_match2.group(1)) if item_match2 else None
                break

        # Special handling banners (e.g. barcode/QR check-in requirements).
        for line in lines:
            lower = line.strip().lower()
            if any(kw in lower for kw in cls.SPECIAL_INSTRUCTION_KEYWORDS):
                result["special_instructions"] = line.strip()
                break

        # Order-size note (e.g. "Contains 1 Extra Small order(s)").
        m = cls.ORDER_SIZE_RE.search(joined)
        if m:
            result["order_size_note"] = m.group(0).strip()

        # Countdown = a bare 1-2 digit line immediately after "Accept".
        for i, line in enumerate(lines):
            if line.strip().lower() == "accept" and i + 1 < len(lines):
                nxt = lines[i + 1].strip()
                if cls.BARE_NUMBER_RE.match(nxt):
                    result["countdown_seconds"] = int(nxt)
                break

        return result

    @staticmethod
    def is_offer_screen(lines):
        """Quick heuristic so the caller only invokes this on the right screen."""
        joined = "\n".join(lines).lower()
        return "accept" in joined and OfferScreenParser.PAYOUT_RE.search(joined) is not None

    # Batch/multi-offer detection -- HONESTY NOTE: unlike the rest of this
    # parser (built from two real single-offer screenshots), this was NOT
    # built from a real batch-offer screenshot -- I don't have one. This
    # detects the SIGNAL that a batch offer is likely showing (so it can
    # be flagged rather than silently mis-parsed as one plain offer, which
    # is what would otherwise happen), but does not attempt to parse
    # separate per-stop payout/distance/restaurant fields -- doing that
    # confidently would need a real example to build against, the same
    # way the single-offer fields were.
    BATCH_KEYWORDS_RE = re.compile(
        r"pickup\s+\d+\s+of\s+\d+|\d+\s+orders?\b|batch\s+order", re.IGNORECASE
    )

    @classmethod
    def is_batch_offer(cls, lines):
        joined = "\n".join(lines)
        return cls.BATCH_KEYWORDS_RE.search(joined) is not None

    # A generic MM:SS pattern -- NOT confirmed against a real offer-screen
    # sample the way DashPauseDetector's "34:01" format is. The Dash
    # Paused screen is confirmed to show a countdown in this format, so
    # it's a reasonable starting guess that the offer acceptance window
    # might too, but this is a best-effort attempt, not a verified parse.
    # If a real sample shows a different format, correct this the same
    # way the notification parser was corrected against reality.
    COUNTDOWN_RE = re.compile(r"\b(\d{1,2}):(\d{2})\b")

    @classmethod
    def extract_countdown_seconds(cls, lines):
        """
        Best-effort: looks for a bare MM:SS pattern anywhere in the offer
        screen text and returns it as total seconds, or None if nothing
        matching is found. Deliberately excludes any line that also looks
        like a clock-time deadline (contains am/pm) to avoid confusing a
        real countdown with "Deliver by 4:25 pm".
        """
        for line in lines:
            if re.search(r"\bam\b|\bpm\b", line, re.IGNORECASE):
                continue
            match = cls.COUNTDOWN_RE.search(line)
            if match:
                minutes, seconds = int(match.group(1)), int(match.group(2))
                if seconds < 60:  # sanity check -- rules out something like "12:345" or a real clock hour>59
                    return minutes * 60 + seconds
        return None

    @staticmethod
    def estimate_minutes_until_deadline(deadline_text, now=None):
        """'4:25 pm' -> minutes from now until that clock time today."""
        if not deadline_text:
            return None
        try:
            deadline_dt = datetime.strptime(deadline_text.strip().upper(), "%I:%M %p")
        except ValueError:
            return None
        now = now or datetime.now()
        candidate = now.replace(hour=deadline_dt.hour, minute=deadline_dt.minute,
                                 second=0, microsecond=0)
        if candidate < now:
            candidate += timedelta(days=1)  # deadline already passed today -> assume tomorrow
        return max(1.0, (candidate - now).total_seconds() / 60.0)

    @staticmethod
    def compute_deadline_timestamp(deadline_text, start_time):
        """
        Same parsing as estimate_minutes_until_deadline, but anchored to
        the trip's real start_time rather than "now" -- needed for
        post-trip reporting, where we're looking BACK at what the
        deadline actually was relative to when this specific delivery
        began, not computing a fresh live estimate. Returns an absolute
        Unix timestamp, or None if deadline_text is missing/unparseable.
        """
        if not deadline_text or not start_time:
            return None
        try:
            deadline_dt = datetime.strptime(deadline_text.strip().upper(), "%I:%M %p")
        except ValueError:
            return None
        start_dt = datetime.fromtimestamp(start_time)
        candidate = start_dt.replace(hour=deadline_dt.hour, minute=deadline_dt.minute,
                                      second=0, microsecond=0)
        if candidate < start_dt:
            candidate += timedelta(days=1)  # deadline is after midnight relative to trip start
        return candidate.timestamp()


class DashPauseDetector:
    """
    Detects "Dash Paused" / "Resume Dash" screen text (via the same
    accessibility-service screen-reading already used for offers), so GPS
    tracking can auto-pause during a paused dash and auto-resume when it
    restarts -- saves battery and keeps driving-efficiency stats from
    being skewed by time spent not actually dashing.

    CONFIRMED against a real "Dash Paused" screenshot: title reads "Dash
    Paused", button reads "Resume dash" -- both patterns below matched
    correctly against the real text. Originally built from pure keyword
    guessing with no real sample; this is one of the few things in this
    file that's since been directly validated, not just assumed.
    """
    PAUSED_RE = re.compile(r"dash\s+(?:has\s+been\s+)?paused|paused\s+dash", re.IGNORECASE)
    RESUMED_RE = re.compile(r"resume\s+dash|dash\s+resumed", re.IGNORECASE)

    @classmethod
    def is_paused_screen(cls, lines):
        joined = "\n".join(lines)
        return cls.PAUSED_RE.search(joined) is not None

    @classmethod
    def is_resumed_screen(cls, lines):
        joined = "\n".join(lines)
        return cls.RESUMED_RE.search(joined) is not None


class DropoffScreenParser:
    """
    Parses the real post-accept "Deliver to X" screen -- built from real
    screenshots (not guessed), closing the single most-flagged gap in
    this whole project: real dropoff address extraction. Previously every
    dropoff used placeholder (0.0, 0.0) coordinates since nothing could
    read the real address after accepting an offer.

    Confirmed screen structure, from two real examples:
        Deliver to {customer_name}
        by {time}
        Call
        Message
        {street_address}              e.g. "88 Caldwell Avenue"
        {suburb}, {state} {postcode}   e.g. "Tarrawanna, NSW 2518"
        Unit/Suite
        {unit_value}                   e.g. "B" or "Unit 23"
        {delivery_instruction}         e.g. "Leave it at the door"

    HONESTY NOTE: built from exactly two real examples. Both were house/
    unit deliveries with a single clear street address -- more unusual
    formats (business addresses, ambiguous unit numbering, apartment
    complexes with building names) haven't been seen and may not parse
    correctly. The full_address field is assembled specifically for
    geocoding (see GoogleApiHelper), combining the street + suburb/state/
    postcode lines exactly as shown, which should geocode reliably for
    real AU addresses even if the finer per-field breakdown is imperfect.
    """
    DELIVER_TO_RE = re.compile(r"^deliver to\s+(.+)$", re.IGNORECASE)
    DEADLINE_RE = re.compile(r"^by\s+(\d{1,2}:\d{2}\s*[ap]m)$", re.IGNORECASE)
    SUBURB_STATE_POSTCODE_RE = re.compile(
        r"^(.+?),\s*(NSW|VIC|QLD|WA|SA|TAS|ACT|NT)\s+(\d{4})$", re.IGNORECASE)
    UNIT_LABEL_RE = re.compile(r"^unit/suite$", re.IGNORECASE)
    # A plausible AU street address: starts with a number, ends in a
    # common street-type word. Deliberately not exhaustive -- built from
    # only two real examples ("88 Caldwell Avenue", "Unit 23/2 Para
    # Street"), so uncommon street-type words will be missed.
    STREET_ADDRESS_RE = re.compile(
        r"^(?:unit\s+\d+/)?\d+[\w\s]*\b(Avenue|Street|Road|Drive|Lane|Place|"
        r"Court|Way|Parade|Close|Circuit|Crescent|Boulevard|Highway)\b",
        re.IGNORECASE)

    @classmethod
    def is_dropoff_screen(cls, lines):
        return any(cls.DELIVER_TO_RE.match(line.strip()) for line in lines)

    @classmethod
    def parse(cls, lines):
        result = {
            "customer_name": None,
            "deadline_text": None,
            "street_address": None,
            "suburb": None,
            "state": None,
            "postcode": None,
            "locality_line": None,
            "unit_suite": None,
            "delivery_instruction": None,
            "full_address": None,
        }

        street_line_index = None
        for i, raw_line in enumerate(lines):
            line = raw_line.strip()

            m = cls.DELIVER_TO_RE.match(line)
            if m:
                result["customer_name"] = m.group(1).strip()
                continue

            m = cls.DEADLINE_RE.match(line)
            if m:
                result["deadline_text"] = m.group(1)
                continue

            m = cls.STREET_ADDRESS_RE.match(line)
            if m and result["street_address"] is None:
                result["street_address"] = line
                street_line_index = i
                continue

            if cls.UNIT_LABEL_RE.match(line) and i + 1 < len(lines):
                result["unit_suite"] = lines[i + 1].strip()
                continue

        # The locality line (suburb, and state/postcode if shown) is
        # identified by POSITION -- the line right after the street
        # address -- rather than requiring the strict "suburb, STATE
        # postcode" format. A real example ("2 Para Street, Balgownie")
        # showed no state or postcode at all, which the stricter format-
        # only match would have silently missed entirely.
        if street_line_index is not None and street_line_index + 1 < len(lines):
            locality_line = lines[street_line_index + 1].strip()
            result["locality_line"] = locality_line
            m = cls.SUBURB_STATE_POSTCODE_RE.match(locality_line)
            if m:
                result["suburb"] = m.group(1).strip()
                result["state"] = m.group(2).upper()
                result["postcode"] = m.group(3)

        # Assembled specifically for geocoding -- a full, real address
        # string, not just a name. This is what actually makes geocoding
        # precise: "88 Caldwell Avenue, Tarrawanna, NSW 2518" resolves to
        # a real point, unlike a bare restaurant name. Falls back to
        # whatever locality text is available even without a confirmed
        # state/postcode -- Google's Geocoding API can usually still
        # resolve "street, suburb" reasonably well without them.
        if result["street_address"] and result["locality_line"]:
            parts = [result["street_address"]]
            if result["unit_suite"] and not result["street_address"].lower().startswith("unit"):
                parts[0] = f"Unit {result['unit_suite']}, {result['street_address']}"
            parts.append(result["locality_line"])
            result["full_address"] = ", ".join(parts)

        # Delivery instruction: any short line that isn't one of the
        # fields above and isn't a UI button label -- best-effort, since
        # this is free-text and not a fixed set of phrases like the
        # offer-screen's special-instruction keywords.
        skip_lines = {"call", "message", "unit/suite"}
        for line in lines:
            stripped = line.strip()
            lower = stripped.lower()
            if (stripped and lower not in skip_lines
                    and not cls.DELIVER_TO_RE.match(stripped)
                    and not cls.DEADLINE_RE.match(stripped)
                    and not cls.STREET_ADDRESS_RE.match(stripped)
                    and not cls.SUBURB_STATE_POSTCODE_RE.match(stripped)
                    and stripped != result.get("unit_suite")
                    and stripped != result.get("street_address")
                    and stripped != result.get("locality_line")
                    and len(stripped) > 3):
                result["delivery_instruction"] = stripped
                break

        return result


class StopsBuffer:
    def __init__(self):
        self._buffer = []  # list of dicts: address, lat, lon, timestamp

    def add(self, address, lat, lon):
        now = time.time()
        self._clear_expired(now)
        self._buffer = [s for s in self._buffer if s["address"] != address]
        self._buffer.insert(0, {"address": address, "lat": lat, "lon": lon, "timestamp": now})
        self._buffer = self._buffer[:STOPS_BUFFER_MAX]

    def find(self, address):
        for s in self._buffer:
            if s["address"] == address:
                return s
        return None

    def _clear_expired(self, now):
        self._buffer = [s for s in self._buffer if now - s["timestamp"] < STOPS_BUFFER_TTL_SECONDS]

    def as_json(self):
        return json.dumps(self._buffer)


# ------------------------------------------------------------------------- #
# Trip state machine
# ------------------------------------------------------------------------- #
class TripManager:
    STATE_IDLE = "IDLE"
    STATE_ACTIVE = "TRIP_ACTIVE"

    def __init__(self, db: Database):
        self.db = db
        self.state = self.STATE_IDLE
        self.gps_points = []       # list of (lat, lon, speed_kmh, ts)
        self.stops = []            # list of dicts: address, lat, lon, matched, arrival_time
        self.events = []
        self.delays = []
        self.messages = []

        self._above_start_speed_since = None
        self._below_stop_speed_since = None
        self._parked_since = None
        self._delay_logged_for_current_park = False
        self._last_point = None
        self.trip_id = None
        self._last_partial_save_ts = 0.0

        # In-memory Welford's-algorithm accel-sample accumulators for THIS
        # trip only -- deliberately NOT written to the DB per GPS tick
        # (unlike self.events above, which is also in-memory-only until
        # trip end): GPS updates arrive as often as 1/sec while driving
        # (see TripForegroundService.GPS_INTERVAL_MOVING_MS), and nothing
        # else in this class does a DB write on every single tick. Merged
        # into the persisted accel_dynamics_history table once, at trip
        # end (see _merge_accel_samples_into_history), same one-write-per-
        # trip cost as the rest of trip persistence.
        self._trip_accel_count = 0
        self._trip_accel_mean = 0.0
        self._trip_accel_m2 = 0.0
        # Loaded once per trip (see _start_trip), not re-queried every
        # tick -- same reasoning as above.
        self._cached_accel_threshold = HARSH_ACCEL_MS2
        self._cached_brake_threshold = HARSH_BRAKE_MS2

        # Dedicated to walking detection specifically -- separate from
        # _parked_since above (which exists for major-delay logging and
        # gets cleared the instant speed ticks up even slightly, so it
        # can't answer "was there a genuine park recently" once walking
        # has actually started).
        self._walking_below_threshold_since = None
        self._walking_last_genuine_park_ts = None
        self._walking_consecutive_pace_count = 0
        self._walking_gap_recorded_for_current_park = False

        # Feeds the "Zero Interaction While Driving" arrival announcement:
        # TTS + floating overlay fire when you arrive at a stop that has
        # pending customer instructions attached to it.
        self.pending_arrival = None
        self._last_message_cutoff = 0.0

        # Feeds the persistent, tappable instruction overlay: shown while
        # APPROACHING a stop (not waiting for arrival), spoken aloud once,
        # and -- per explicit request -- does NOT auto-clear even after
        # arrival, since the delivery may not actually be complete yet.
        # Only clears when manually tapped away (see OverlayHelper's
        # dedicated persistent/tappable overlay method).
        self.pending_approach_instruction = None
        self._approach_instruction_shown_for_stop_ids = set()
        self._last_gap_sample_log = None
        self._last_phase_capture_log = None
        self._last_gap_restaurant_name = None
        self._last_gap_seconds = None

        # Dual-mode support: DASHER mode (Dasher app active / delivery in
        # progress) vs GENERAL mode (plain driving-efficiency tracking).
        # dasher_app_foreground is set by DasherAccessibilityService based on
        # which app currently has focus. _trip_mode is a per-trip snapshot
        # that upgrades to DASHER (and stays there) if Dasher becomes active
        # partway through an already-started trip.
        self.dasher_app_foreground = False
        self._trip_mode = "GENERAL"

        # Pickup-location tracking: measures real time-at-pickup (parking +
        # waiting for the order, which GPS can't tell apart -- see
        # SmartScoreEngine.record_restaurant_wait docstring) so restaurant
        # wait estimates become genuine learned data instead of a constant.
        self.pickup = None  # dict: restaurant_name, lat, lon, arrived_at, recorded

        # Real-distance tracking: measures actual km traveled from trip
        # start to pickup arrival (deadhead) and from pickup departure to
        # trip end (delivery), so the offer screen's claimed distance can be
        # empirically checked against what really happened -- rather than
        # guessing whether that figure includes deadhead or not.
        self._cumulative_distance_km = 0.0
        self._deadhead_distance_km = None       # snapshot at pickup arrival
        self._distance_at_departure_km = None   # snapshot at pickup departure
        self._departure_timestamp = None        # real-clock time at departure

    # -- public API called from Java/Kotlin -----------------------------
    def on_gps_update(self, lat, lon, speed_kmh, timestamp_ms):
        ts = timestamp_ms / 1000.0
        pickup_wait_event = None
        delivery_speed_event = None

        if self.state == self.STATE_IDLE:
            self._evaluate_trip_start(speed_kmh, ts, lat, lon)
        else:
            if self.get_mode() == "DASHER":
                self._trip_mode = "DASHER"  # upgrade-only: sticky once true
            self._process_point_during_trip(lat, lon, speed_kmh, ts)
            pickup_wait_event = self._evaluate_pickup(lat, lon, ts)
            self._evaluate_arrivals(lat, lon, ts)
            delivery_speed_event = self._evaluate_trip_end(speed_kmh, ts)
            self._maybe_save_partial_progress(ts)

        self._last_point = (lat, lon, speed_kmh, ts)
        return pickup_wait_event, delivery_speed_event

    _PARTIAL_SAVE_INTERVAL_SECONDS = 20

    def _maybe_save_partial_progress(self, ts):
        """
        Periodically writes the trip's CURRENT accumulated stats to the
        database while it's still in progress -- not just at the end.

        Previously, a trip's row only ever got real data (distance,
        safety events, everything) via ONE update at the very end
        (_persist_trip); if the app crashed mid-trip, that update never
        happened, and the row was left with nothing but its starting
        time/location forever -- functionally invisible and with zero
        real data to recover, since nothing had ever been incrementally
        saved. This closes that gap: even if the app dies mid-delivery,
        whatever was true as of the last periodic save (at most
        _PARTIAL_SAVE_INTERVAL_SECONDS old) is genuinely recoverable, not
        just theoretically so.
        """
        if self.trip_id is None:
            return
        if ts - self._last_partial_save_ts < self._PARTIAL_SAVE_INTERVAL_SECONDS:
            return
        self._last_partial_save_ts = ts
        summary = self._compute_summary(ts)
        self.db.conn.execute("""
            UPDATE trips SET distance_km=?, moving_seconds=?, slow_seconds=?,
                stopped_seconds=?, time_efficiency_score=?, safety_score=?,
                geofence_hit_ratio=?, composite_score=?, fuel_cost_estimate=?
            WHERE id=?
        """, (summary["distance_km"], summary["moving_seconds"], summary["slow_seconds"],
              summary["stopped_seconds"], summary["time_efficiency_score"], summary["safety_score"],
              summary["geofence_hit_ratio"], summary["composite_score"], summary["fuel_cost_estimate"],
              self.trip_id))
        self.db.conn.commit()

    def add_pickup(self, restaurant_name, lat, lon, claimed_distance_km=None, score_snapshot_json=None,
                   deadline_text=None, address=None):
        """
        Called once per delivery when the pickup location is known (e.g.
        from the offer screen). lat/lon start as placeholders (0.0, 0.0)
        until real geocoding resolves (see update_pickup_coordinates) --
        same limitation as add_stop's dropoff address; see README.
        claimed_distance_km is whatever the offer screen showed (e.g.
        "5.1 km") -- stored so it can be compared against the real
        measured distance once the trip completes.
        score_snapshot_json: the full Smart Score breakdown for the offer
        that led to this pickup, carried through to the trip that starts
        shortly after (see _start_trip) so it can be shown in full once
        the trip completes.
        deadline_text: the offer's real "Deliver by X pm" text, if the
        screen showed one -- carried through the same way, so the
        post-trip phase breakdown can compare real elapsed time against
        what was actually promised.
        address: a real formatted street address, if already known at
        call time -- usually None here, since the only thing parsed off
        the offer screen is the restaurant NAME, not an address; the real
        value normally arrives slightly later via update_pickup_address
        once GoogleApiHelper's geocoding resolves.
        """
        self.pickup = {
            "restaurant_name": restaurant_name, "lat": lat, "lon": lon,
            "arrived_at": None, "recorded": False,
            "claimed_distance_km": claimed_distance_km,
            "score_snapshot_json": score_snapshot_json,
            "deadline_text": deadline_text,
            "address": address,
        }
        self._deadhead_distance_km = None
        self._distance_at_departure_km = None
        self._departure_timestamp = None

    def update_pickup_coordinates(self, lat, lon):
        """
        Called once real geocoding resolves (asynchronously, from
        GoogleApiHelper), replacing the (0.0, 0.0) placeholder with real
        coordinates for the CURRENT pickup -- as long as arrival hasn't
        already been detected against the placeholder in the meantime.
        This is what actually makes arrival detection (and everything
        built on top of it: deadhead, wait time, delivery speed learning)
        work on a real delivery instead of only in simulated testing.
        """
        if self.pickup and self.pickup["arrived_at"] is None:
            self.pickup["lat"] = lat
            self.pickup["lon"] = lon

    def update_pickup_address(self, address):
        """
        Called once GoogleApiHelper's geocode-with-formatted-address
        resolves for the current pickup's restaurant name -- same "only
        while still relevant" guard as update_pickup_coordinates (once
        arrived, the address this is about no longer needs to keep
        changing). Unlike coordinates, this is also persisted straight to
        the currently active trip row (if one exists yet) so it survives
        even if resolution lands mid-trip rather than before departure --
        there's no separate "trip finished, backfill everything" pass the
        way there is for score_snapshot_json/deadline_text, which are
        only ever read once, at _start_trip.
        """
        if self.pickup and self.pickup["arrived_at"] is None:
            self.pickup["address"] = address
        self._update_current_trip_text_column("pickup_address", address)

    def _evaluate_pickup(self, lat, lon, ts):
        """
        Detects arrival at, then departure from, the pickup location, and
        returns {"restaurant_name":..., "wait_minutes":...} exactly once
        when departure is detected -- the caller (DriveMonitorEngine) uses
        this to call SmartScoreEngine.record_restaurant_wait(). Also
        snapshots real cumulative distance and clock time at both moments,
        so deadhead/delivery distance AND real delivery speed can be
        measured for real (see _cumulative_distance_km).
        """
        if not self.pickup or self.pickup["recorded"]:
            return None
        distance = haversine_meters(lat, lon, self.pickup["lat"], self.pickup["lon"])
        within_geofence = distance <= ARRIVAL_GEOFENCE_METERS

        if within_geofence and self.pickup["arrived_at"] is None:
            self.pickup["arrived_at"] = ts
            self._deadhead_distance_km = self._cumulative_distance_km
            self._update_current_trip_phase_timestamp("pickup_arrival_ts", ts)
            return None

        if not within_geofence and self.pickup["arrived_at"] is not None:
            wait_minutes = (ts - self.pickup["arrived_at"]) / 60.0
            self.pickup["recorded"] = True
            self._distance_at_departure_km = self._cumulative_distance_km
            self._departure_timestamp = ts
            self._update_current_trip_phase_timestamp("pickup_departure_ts", ts)
            return {
                "restaurant_name": self.pickup["restaurant_name"],
                "wait_minutes": wait_minutes,
            }
        return None

    def _update_current_trip_phase_timestamp(self, column_name, ts):
        """
        Persists a real phase-timing moment (pickup arrival/departure,
        dropoff arrival, walking confirmed) to whichever trip is
        currently active -- feeds the post-trip "where did the time go"
        breakdown. column_name is always one of a small, fixed set of
        real column names (never user input), so this is safe despite
        the f-string.
        """
        self.db.conn.execute(
            f"UPDATE trips SET {column_name} = ? WHERE end_time IS NULL", (ts,)
        )
        self.db.conn.commit()

    def _update_current_trip_text_column(self, column_name, value):
        """
        Same shape as _update_current_trip_phase_timestamp, for a text
        value (currently only pickup_address) rather than a timestamp. A
        no-op if no trip is currently active yet -- update_pickup_address
        can resolve before departure, in which case _start_trip picks up
        self.pickup["address"] directly instead.
        """
        self.db.conn.execute(
            f"UPDATE trips SET {column_name} = ? WHERE end_time IS NULL", (value,)
        )
        self.db.conn.commit()
        # GAP 3 (diagnostic-coverage pass): previously this write was
        # completely silent -- consumed by DriveMonitorEngine.on_gps_update
        # to log it, since TripManager has no direct access to
        # log_diagnostic itself.
        #
        # Confirmed real bug, fixed here: this referenced `ts`, a
        # copy-paste leftover from _update_current_trip_phase_timestamp
        # just above (which has a real `ts` parameter) -- this method has
        # no such variable, so every real call raised
        # NameError: name 'ts' is not defined, crashing
        # update_pickup_address mid-geocode-callback on a real device
        # (confirmed via a real diagnostic log, 2026-08-22 16:45:11).
        self._last_phase_capture_log = f"Captured {column_name} = {value}"

    def get_mode(self):
        """
        Current mode, live (not the per-trip snapshot). DASHER if the
        Dasher app is currently in the foreground, OR there's an unmatched
        delivery stop pending (covers briefly alt-tabbing to Maps mid-
        delivery), OR a pickup is actively in progress (registered but not
        yet departed) -- this last one matters because a long pickup wait
        (parked, near-zero speed) would otherwise satisfy GENERAL mode's
        "parked 5+ minutes ends the trip" rule and prematurely end the
        delivery before the driver even leaves the restaurant. Otherwise
        GENERAL (plain driving-efficiency tracking).
        """
        has_pending_stop = any(not s["matched"] for s in self.stops)
        has_active_pickup = self.pickup is not None and not self.pickup.get("recorded")
        if self.dasher_app_foreground or has_pending_stop or has_active_pickup:
            return "DASHER"
        return "GENERAL"

    def set_dasher_foreground(self, is_foreground):
        self.dasher_app_foreground = bool(is_foreground)

    def add_stop(self, address, lat, lon):
        self.stops.append({
            "address": address, "lat": lat, "lon": lon,
            "matched": False, "arrival_time": None,
        })

    def take_pending_arrival(self):
        """Consumed once by DriveMonitorEngine.on_gps_update() so each
        arrival announcement fires exactly once."""
        result = self.pending_arrival
        self.pending_arrival = None
        return result

    def on_message(self, package_name, sender, body, timestamp_ms, is_messaging_style, lat=None, lon=None):
        """
        lat/lon: the current known position when this message arrived,
        if available -- used to tag the message with the CLOSEST stop at
        that moment (a real stop identifier), fixing the previous
        limitation where a batch order's messages were only ever matched
        by time window against whichever stop happened to be approached
        LATER, regardless of which stop the message was actually about.

        HONEST LIMIT: this is still a heuristic, not a certainty -- it
        assumes the closest stop at message-arrival time is the one the
        message is about, which is a reasonable assumption (a customer
        messaging you is usually near their own delivery address) but
        not a guarantee. It's a meaningfully better signal than time
        alone, not a perfect one. If lat/lon aren't provided, or no
        stops are registered yet, stop_id is None and the original
        time-window-only matching in _check_approach_instruction is
        used as a fallback -- single deliveries are unaffected either
        way, since there's only ever one possible stop to match.
        """
        if not MessageIntelligence.classify(package_name, is_messaging_style):
            return None
        instruction = MessageIntelligence.extract_instruction(body)
        stop_id = None
        if lat is not None and lon is not None and self.stops:
            closest_stop = min(
                self.stops, key=lambda s: haversine_meters(lat, lon, s["lat"], s["lon"])
            )
            stop_id = id(closest_stop)
        self.messages.append({
            "sender": sender, "body": body,
            "timestamp": timestamp_ms / 1000.0,
            "extracted_instruction": instruction,
            "stop_id": stop_id,
        })
        return instruction

    # -- internal state machine -----------------------------------------
    def _evaluate_trip_start(self, speed_kmh, ts, lat, lon):
        if speed_kmh > TRIP_START_SPEED_KMH:
            if self._above_start_speed_since is None:
                self._above_start_speed_since = ts
            elif ts - self._above_start_speed_since >= TRIP_START_HOLD_SECONDS:
                self._start_trip(ts, lat, lon)
        else:
            self._above_start_speed_since = None

    def _start_trip(self, ts, lat, lon):
        self.state = self.STATE_ACTIVE
        self.gps_points = []
        self.stops = [s for s in self.stops if not s["matched"]]
        self.events = []
        self.delays = []
        self._below_stop_speed_since = None
        self._parked_since = None
        self._delay_logged_for_current_park = False
        self.pending_arrival = None
        self._last_message_cutoff = ts
        self._trip_mode = self.get_mode()
        self._cumulative_distance_km = 0.0
        self._trip_accel_count = 0
        self._trip_accel_mean = 0.0
        self._trip_accel_m2 = 0.0
        # One DB read per trip, not per tick -- see the attribute comment
        # in __init__.
        self._cached_accel_threshold, self._cached_brake_threshold, _, _ = (
            self._learned_accel_brake_thresholds()
        )
        self._deadhead_distance_km = None
        self._distance_at_departure_km = None
        self._departure_timestamp = None
        self._last_partial_save_ts = ts
        # Only clear pickup tracking if it's stale (already recorded from a
        # prior delivery) -- a pending pickup set just before driving starts
        # (the normal flow: accept offer while parked, then drive) must
        # survive into the new trip, not get wiped here.
        if self.pickup and self.pickup.get("recorded"):
            self.pickup = None

        score_snapshot = self.pickup.get("score_snapshot_json") if self.pickup else None
        deadline_text = self.pickup.get("deadline_text") if self.pickup else None
        pickup_address = self.pickup.get("address") if self.pickup else None
        cur = self.db.conn.execute(
            "INSERT INTO trips (start_time, mode, start_lat, start_lon, offer_score_snapshot_json, "
            "deadline_text, pickup_address) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (ts, self._trip_mode, lat, lon, score_snapshot, deadline_text, pickup_address)
        )
        self.db.conn.commit()
        self.trip_id = cur.lastrowid

    def _process_point_during_trip(self, lat, lon, speed_kmh, ts):
        classification = self._classify_speed(speed_kmh)
        if self.gps_points:
            prev_lat, prev_lon = self.gps_points[-1][0], self.gps_points[-1][1]
            self._cumulative_distance_km += haversine_meters(prev_lat, prev_lon, lat, lon) / 1000.0
        self.gps_points.append((lat, lon, speed_kmh, ts, classification))
        self._detect_harsh_events(speed_kmh, ts, lat, lon)
        self._detect_major_delay(speed_kmh, ts, lat, lon)

    @staticmethod
    def _classify_speed(speed_kmh):
        if speed_kmh > 15:
            return "moving"
        if speed_kmh >= 1:
            return "slow"
        return "stopped"

    def _detect_harsh_events(self, speed_kmh, ts, lat, lon):
        if not self._last_point:
            return
        _, _, last_speed, last_ts, *_ = (*self._last_point, None)
        dt = ts - last_ts
        if dt <= 0:
            return
        dv_ms = (speed_kmh - last_speed) * (1000.0 / 3600.0)
        accel = dv_ms / dt
        # Personalized once enough real samples exist -- see
        # _learned_accel_brake_thresholds. Uses the threshold cached at
        # _start_trip, not re-queried per tick (see __init__'s comment on
        # _cached_accel_threshold).
        if accel > self._cached_accel_threshold:
            self._log_event("harsh_accel", lat, lon, ts, accel)
        elif accel < self._cached_brake_threshold:
            self._log_event("harsh_brake", lat, lon, ts, accel)
        if speed_kmh > DEFAULT_SPEED_LIMIT_KMH:
            self._log_event("speeding", lat, lon, ts, speed_kmh)
        self._record_accel_sample_in_memory(accel)

    def _log_event(self, event_type, lat, lon, ts, magnitude):
        self.events.append({
            "event_type": event_type, "lat": lat, "lon": lon,
            "timestamp": ts, "magnitude": magnitude,
        })

    def _record_accel_sample_in_memory(self, accel):
        """
        Welford's online algorithm, updated in memory only (see __init__'s
        comment on why this never writes to the DB per tick) -- folds
        every real per-tick accel sample into THIS trip's running mean/M2,
        regardless of whether it crossed the harsh threshold. Learning a
        personal baseline from only-already-harsh samples would be
        circular: the current threshold would gate what data the next
        threshold gets learned from, never converging on your actual
        normal driving distribution.
        """
        self._trip_accel_count += 1
        delta = accel - self._trip_accel_mean
        self._trip_accel_mean += delta / self._trip_accel_count
        delta2 = accel - self._trip_accel_mean
        self._trip_accel_m2 += delta * delta2

    def _learned_accel_brake_thresholds(self):
        """
        Returns (harsh_accel_ms2, harsh_brake_ms2, sample_count,
        is_learned). Falls back to the fixed HARSH_ACCEL_MS2/
        HARSH_BRAKE_MS2 defaults until ACCEL_BRAKE_MIN_SAMPLES_TO_LEARN
        real per-tick accel samples have been recorded across all trips
        (see _merge_accel_samples_into_history) -- same "start generic,
        replace with a real personal baseline once there's enough data"
        pattern as deadhead/wait-time/delivery-speed/peak-hour, just at
        per-tick rather than per-trip granularity, so it needs a much
        larger sample count (ACCEL_BRAKE_MIN_SAMPLES_TO_LEARN) before
        it's trustworthy.
        """
        row = self.db.conn.execute(
            "SELECT sample_count, mean_accel_ms2, mean_squared_diff FROM accel_dynamics_history WHERE id = 1"
        ).fetchone()
        sample_count = row["sample_count"] if row and row["sample_count"] else 0
        if sample_count < ACCEL_BRAKE_MIN_SAMPLES_TO_LEARN:
            return HARSH_ACCEL_MS2, HARSH_BRAKE_MS2, sample_count, False

        variance = row["mean_squared_diff"] / sample_count
        std = variance ** 0.5
        mean = row["mean_accel_ms2"]
        accel_threshold = max(ACCEL_BRAKE_MIN_THRESHOLD_MS2, mean + ACCEL_BRAKE_STD_MULTIPLIER * std)
        brake_threshold = min(-ACCEL_BRAKE_MIN_THRESHOLD_MS2, mean - ACCEL_BRAKE_STD_MULTIPLIER * std)
        return accel_threshold, brake_threshold, sample_count, True

    def _merge_accel_samples_into_history(self):
        """
        Folds this trip's in-memory accel Welford summary into the
        persisted cross-trip one -- ONE DB read + ONE DB write, at trip
        end, not per tick (see __init__'s comment). Uses Chan et al.'s
        parallel-variance combine formula to merge two independent
        Welford summaries (this trip's, and everything before it) into
        one, rather than needing every individual raw sample.
        """
        if self._trip_accel_count == 0:
            return
        row = self.db.conn.execute(
            "SELECT sample_count, mean_accel_ms2, mean_squared_diff FROM accel_dynamics_history WHERE id = 1"
        ).fetchone()
        if row and row["sample_count"]:
            count_a, mean_a, m2_a = row["sample_count"], row["mean_accel_ms2"], row["mean_squared_diff"]
            count_b, mean_b, m2_b = self._trip_accel_count, self._trip_accel_mean, self._trip_accel_m2
            count = count_a + count_b
            delta = mean_b - mean_a
            mean = mean_a + delta * count_b / count
            m2 = m2_a + m2_b + (delta ** 2) * count_a * count_b / count
        else:
            count, mean, m2 = self._trip_accel_count, self._trip_accel_mean, self._trip_accel_m2
        self.db.conn.execute("""
            INSERT INTO accel_dynamics_history (id, sample_count, mean_accel_ms2, mean_squared_diff)
            VALUES (1, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET sample_count = excluded.sample_count,
                mean_accel_ms2 = excluded.mean_accel_ms2, mean_squared_diff = excluded.mean_squared_diff
        """, (count, mean, m2))
        self.db.conn.commit()

    def _detect_major_delay(self, speed_kmh, ts, lat, lon):
        """
        Logs a major delay exactly once per continuous parked stretch that
        crosses MAJOR_DELAY_SECONDS -- a real bug fix, not a design choice.
        The previous version had no "already logged" guard, so once the
        threshold was crossed it appended a NEW delay record on every
        single subsequent GPS tick for as long as the vehicle stayed
        parked (e.g. 181 "delays" for one continuous 5-minute stop),
        discovered only once this data was actually surfaced in the trip
        summary for the first time.
        """
        if speed_kmh < 1:
            if self._parked_since is None:
                self._parked_since = ts
                self._delay_logged_for_current_park = False
            elif not self._delay_logged_for_current_park and ts - self._parked_since >= MAJOR_DELAY_SECONDS:
                self.delays.append({
                    "lat": lat, "lon": lon,
                    "duration_seconds": int(ts - self._parked_since),
                    "timestamp": ts,
                })
                self._delay_logged_for_current_park = True
        else:
            self._parked_since = None
            self._delay_logged_for_current_park = False

    def _evaluate_arrivals(self, lat, lon, ts):
        if self._below_stop_speed_since is None:
            return
        if ts - self._below_stop_speed_since < TRIP_STOP_HOLD_SECONDS:
            return
        nearest, nearest_dist = None, float("inf")
        for stop in self.stops:
            if stop["matched"]:
                continue
            d = haversine_meters(lat, lon, stop["lat"], stop["lon"])
            if d < nearest_dist:
                nearest, nearest_dist = stop, d
        if nearest and nearest_dist <= ARRIVAL_GEOFENCE_METERS:
            nearest["matched"] = True
            nearest["arrival_time"] = ts
            # Only the FIRST dropoff arrival -- a multi-stop batch would
            # have several, and this is deliberately a simplified,
            # single-primary-flow breakdown, not a full per-stop one.
            cursor = self.db.conn.execute(
                "UPDATE trips SET dropoff_arrival_ts = ? WHERE end_time IS NULL AND dropoff_arrival_ts IS NULL",
                (ts,),
            )
            self.db.conn.commit()
            # GAP 3 (diagnostic-coverage pass): previously silent. Only
            # set if a row was genuinely updated -- the guard clause above
            # means this can legitimately affect zero rows for a second
            # stop in a batch, which isn't a real capture worth logging.
            if cursor.rowcount > 0:
                self._last_phase_capture_log = f"Captured dropoff_arrival_ts = {ts}"

            # Any customer instructions received since the last arrival
            # belong to this stop -- surface them via TTS + overlay.
            # FIXED (previously the same known limitation as
            # _check_approach_instruction): now matches by the real
            # stop_id when available, not just time window -- see
            # on_message for how stop_id gets assigned.
            arrival_stop_id = id(nearest)
            instructions = [
                m["extracted_instruction"] for m in self.messages
                if m["extracted_instruction"]
                and self._last_message_cutoff < m["timestamp"] <= ts
                and (m.get("stop_id") is None or m["stop_id"] == arrival_stop_id)
            ]
            self._last_message_cutoff = ts
            if instructions:
                self.pending_arrival = {
                    "address": nearest.get("address", ""),
                    "instructions": instructions,
                }

    def _check_approaching_stop(self, lat, lon):
        """
        Finds the nearest UNMATCHED stop within APPROACHING_RADIUS_METERS,
        independent of speed/hold-time (unlike _evaluate_arrivals) -- this
        is meant to fire WHILE STILL DRIVING, as a heads-up before you
        actually arrive, not after. Returns the stop dict or None.
        """
        nearest, nearest_dist = None, float("inf")
        for stop in self.stops:
            if stop["matched"]:
                continue
            d = haversine_meters(lat, lon, stop["lat"], stop["lon"])
            if d < nearest_dist:
                nearest, nearest_dist = stop, d
        if nearest and nearest_dist <= APPROACHING_RADIUS_METERS:
            return nearest
        return None

    def check_approaching_pickup(self, lat, lon):
        """
        Same idea as _check_approaching_stop, but for the single active
        pickup rather than a list of dropoff stops -- previously had no
        equivalent at all, despite dropoff having had one from early on.
        Returns a dict with restaurant_name/address/lat/lon while
        genuinely approaching (within APPROACHING_RADIUS_METERS) and not
        yet arrived, else None. "address" can legitimately be None here
        even while otherwise approaching -- the restaurant name geocodes
        to real coordinates fast (needed for arrival detection itself),
        but the separate formatted-address lookup can still be in flight;
        the caller uses a None address as its "waiting for address" signal
        rather than this method blocking on it.
        """
        if not self.pickup or self.pickup["arrived_at"] is not None:
            return None
        if self.pickup["lat"] == 0.0 and self.pickup["lon"] == 0.0:
            return None  # still a placeholder -- nothing real to approach yet
        d = haversine_meters(lat, lon, self.pickup["lat"], self.pickup["lon"])
        if d > APPROACHING_RADIUS_METERS:
            return None
        return {
            "restaurant_name": self.pickup["restaurant_name"],
            "address": self.pickup.get("address"),
            "lat": self.pickup["lat"],
            "lon": self.pickup["lon"],
        }

    def _check_approach_instruction(self, approaching_stop, ts):
        """
        Feeds the persistent, tappable instruction overlay (per explicit
        request): triggers once per stop, the moment it's first detected
        as approaching (see _check_approaching_stop), rather than waiting
        for arrival.

        FIXED (previously a known limitation): messages are now matched
        by a real stop_id (the closest stop at message-arrival time, see
        on_message) when available, not just by time window -- so a
        batch order's messages correctly attach to the stop they were
        actually about, even if you're approaching a different stop
        first. Falls back to the original time-window-only matching for
        messages where stop_id is None (no position was known, or no
        stops existed yet when the message arrived) -- single deliveries
        are unaffected either way, since there's only ever one possible
        stop to match.

        Deliberately keyed on Python object identity (id()) rather than
        address/coordinates, since stop dicts are mutated in place
        throughout a trip, not recreated -- this reliably tracks "have we
        already shown this for THIS specific stop" without needing a
        separate id field on the stop dict itself.
        """
        if approaching_stop is None:
            return
        stop_id = id(approaching_stop)
        if stop_id in self._approach_instruction_shown_for_stop_ids:
            return  # already shown for this specific stop, don't repeat

        instructions = [
            m["extracted_instruction"] for m in self.messages
            if m["extracted_instruction"]
            and self._last_message_cutoff < m["timestamp"] <= ts
            and (m.get("stop_id") is None or m["stop_id"] == stop_id)
        ]
        if instructions:
            self._approach_instruction_shown_for_stop_ids.add(stop_id)
            self.pending_approach_instruction = {
                "address": approaching_stop.get("address", ""),
                "instructions": instructions,
            }

    def take_pending_approach_instruction(self):
        """Consumed once by DriveMonitorEngine.on_gps_update() so each approach-instruction fires exactly once."""
        result = self.pending_approach_instruction
        self.pending_approach_instruction = None
        return result

    def get_last_parking_gap_for_feedback(self):
        """
        Read-only (NOT consumed like the other pending signals above) --
        the post-trip feedback screen only shows at trip END, much later
        than the next GPS tick, so this must survive until then rather
        than being cleared automatically. Returns None if no park-to-walk
        gap was ever recorded this session.
        """
        if self._last_gap_restaurant_name is None:
            return None
        return {"restaurant_name": self._last_gap_restaurant_name, "gap_seconds": self._last_gap_seconds}

    def clear_last_parking_gap_for_feedback(self):
        """
        Called once the feedback screen has been shown and dismissed
        (whether or not the difficulty question was actually answered) --
        prevents the SAME gap from being shown again on a later, unrelated
        trip's feedback if it somehow wasn't cleared properly the first time.
        """
        self._last_gap_restaurant_name = None
        self._last_gap_seconds = None

    def _learned_walking_speed_threshold_kmh(self):
        """
        Returns (threshold_kmh, sample_count, is_learned). Same pattern as
        delivery speed and deadhead: starts from a physically-grounded
        default (see DEFAULT_WALKING_SPEED_THRESHOLD_KMH), replaced by a
        real learned value once enough samples exist. The learned
        threshold is set a bit above the learned average (not equal to
        it), since a plain average would misclassify your OWN faster-
        than-average strides as "not walking" -- the goal is an upper
        bound that comfortably covers real walking, not just the mean.
        """
        row = self.db.conn.execute(
            "SELECT avg_speed_kmh, sample_count FROM walking_speed_history WHERE id = 1"
        ).fetchone()
        if row and row["sample_count"] and row["sample_count"] >= WALKING_SPEED_MIN_SAMPLES_TO_LEARN:
            return row["avg_speed_kmh"] * 1.3, row["sample_count"], True
        return DEFAULT_WALKING_SPEED_THRESHOLD_KMH, row["sample_count"] if row else 0, False

    def _record_walking_speed_sample(self, speed_kmh):
        """
        Called only from within the walking-window itself (see
        is_walking_pace) -- every speed reading that already qualifies as
        "plausibly walking" under the CURRENT threshold (default or
        already-learned) gets folded into the running average, gradually
        refining what "walking pace" actually means for how this specific
        phone/user tends to move, rather than staying a fixed guess forever.
        """
        if speed_kmh < WALKING_SPEED_MIN_KMH:
            return
        row = self.db.conn.execute(
            "SELECT avg_speed_kmh, sample_count FROM walking_speed_history WHERE id = 1"
        ).fetchone()
        if row and row["sample_count"]:
            new_count = row["sample_count"] + 1
            new_avg = ((row["avg_speed_kmh"] * row["sample_count"]) + speed_kmh) / new_count
        else:
            new_avg, new_count = speed_kmh, 1
        self.db.conn.execute("""
            INSERT INTO walking_speed_history (id, avg_speed_kmh, sample_count) VALUES (1, ?, ?)
            ON CONFLICT(id) DO UPDATE SET avg_speed_kmh = excluded.avg_speed_kmh, sample_count = excluded.sample_count
        """, (new_avg, new_count))
        self.db.conn.commit()

    def _learned_recently_parked_window_seconds(self):
        """
        Returns (window_seconds, sample_count, is_learned). Same pattern
        as the walking-speed threshold: starts from the fixed default
        (WALKING_RECENTLY_PARKED_WINDOW_SECONDS), replaced by a real
        learned value once enough park-to-walk gaps have actually been
        observed. The learned window is set a bit above the learned
        average gap (not equal to it) -- a buffer so a slightly-longer-
        than-usual walk to the door doesn't fall just outside the window
        and get missed, while still tightening the generous fixed 5-minute
        default toward what this user's real pattern actually looks like.
        """
        row = self.db.conn.execute(
            "SELECT avg_gap_seconds, sample_count FROM park_to_walk_gap_history WHERE id = 1"
        ).fetchone()
        if row and row["sample_count"] and row["sample_count"] >= PARK_TO_WALK_GAP_MIN_SAMPLES_TO_LEARN:
            return row["avg_gap_seconds"] * 2.0, row["sample_count"], True
        return WALKING_RECENTLY_PARKED_WINDOW_SECONDS, row["sample_count"] if row else 0, False

    def _record_park_to_walk_gap_sample(self, gap_seconds, restaurant_name=None):
        """
        Records the real time elapsed between a genuine park being
        confirmed and walking actually being confirmed -- recorded
        exactly ONCE per park event (see the recorded-flag check in
        is_walking_pace), not on every tick while still walking.
        """
        row = self.db.conn.execute(
            "SELECT avg_gap_seconds, sample_count FROM park_to_walk_gap_history WHERE id = 1"
        ).fetchone()
        if row and row["sample_count"]:
            new_count = row["sample_count"] + 1
            new_avg = ((row["avg_gap_seconds"] * row["sample_count"]) + gap_seconds) / new_count
        else:
            new_avg, new_count = gap_seconds, 1
        self.db.conn.execute("""
            INSERT INTO park_to_walk_gap_history (id, avg_gap_seconds, sample_count) VALUES (1, ?, ?)
            ON CONFLICT(id) DO UPDATE SET avg_gap_seconds = excluded.avg_gap_seconds, sample_count = excluded.sample_count
        """, (new_avg, new_count))
        self.db.conn.commit()
        # Remembered for the post-trip parking-difficulty confirmation
        # prompt (see take_last_parking_gap_for_feedback) -- a raw
        # duration alone can't tell a genuinely difficult park apart from
        # an easy one that's just naturally far from the door, so this
        # gets confirmed/corrected by the user afterward, per restaurant.
        self._last_gap_restaurant_name = restaurant_name
        self._last_gap_seconds = gap_seconds
        # Consumed by DriveMonitorEngine.on_gps_update to log this --
        # TripManager has no direct access to log_diagnostic itself.
        # Previously zero trace of this learning mechanism ever running.
        just_crossed_threshold = new_count == PARK_TO_WALK_GAP_MIN_SAMPLES_TO_LEARN
        self._last_gap_sample_log = (
            f"Recorded park-to-walk gap: {gap_seconds:.0f}s (sample {new_count})"
            + (" -- learned window now active" if just_crossed_threshold else "")
        )

    def is_walking_pace(self, lat, lon, speed_kmh, ts=None):
        """
        The real-time check driving the purple "walking" status dot.
        DASHER mode only, per explicit request -- GENERAL mode driving
        never shows this regardless of speed.

        RETROSPECTIVE, not single-instant: a single slow GPS reading can't
        tell "parked, now walking" apart from "car briefly slowed in
        traffic" -- both look identical in isolation. This requires:
        (1) currently in DASHER mode, (2) within APPROACHING_RADIUS of an
        unmatched stop but NOT yet within ARRIVAL_GEOFENCE, (3) a GENUINE
        preceding park -- sustained near-zero speed for at least
        WALKING_MIN_PARK_SECONDS, not just one low reading (stop-and-go
        traffic rarely holds this still this long), within a window that
        starts as WALKING_RECENTLY_PARKED_WINDOW_SECONDS but tightens
        toward your own learned typical park-to-walk gap once enough real
        examples exist (see _learned_recently_parked_window_seconds), and
        (4) the walking-pace speed itself sustained across
        WALKING_PATTERN_CONSECUTIVE_READINGS readings, not a single noisy
        blip. Exiting back to vehicle speed stays immediate -- no one
        walking suddenly moves at real vehicle speed, so that transition
        is unambiguous in one reading.

        ts defaults to wall-clock time if not provided (matches the
        pattern used elsewhere for optional timestamps in tests).
        """
        if ts is None:
            ts = time.time()

        # Reverted to DASHER-only, the original explicit requirement --
        # ran in GENERAL mode too for an extended field-testing period
        # with no reported issues, so this is now considered confirmed
        # working correctly.
        if self.get_mode() != "DASHER":
            self._walking_consecutive_pace_count = 0
            return False
        nearest = self._check_approaching_stop(lat, lon)
        if nearest is None:
            self._walking_consecutive_pace_count = 0
            return False
        distance_to_stop = haversine_meters(lat, lon, nearest["lat"], nearest["lon"])
        if distance_to_stop <= ARRIVAL_GEOFENCE_METERS:
            self._walking_consecutive_pace_count = 0
            return False  # already close enough to count as arrived, not "still walking over"

        threshold_kmh, _, _ = self._learned_walking_speed_threshold_kmh()

        # Track genuine parking: sustained near-zero speed, separate from
        # _parked_since (which exists for major-delay logging and gets
        # cleared the instant speed ticks up even slightly).
        if speed_kmh < WALKING_SPEED_MIN_KMH:
            if self._walking_below_threshold_since is None:
                self._walking_below_threshold_since = ts
            elif (ts - self._walking_below_threshold_since >= WALKING_MIN_PARK_SECONDS
                    and self._walking_last_genuine_park_ts is None):
                self._walking_last_genuine_park_ts = ts
                self._walking_gap_recorded_for_current_park = False
            # Genuinely stationary right now -- not yet walking, but not
            # reset either (still within a real park).
            self._walking_consecutive_pace_count = 0
            return False
        else:
            self._walking_below_threshold_since = None

        # No genuine park recently enough -- can't be "walking to the
        # door", since that always follows an actual stop. This is what
        # rules out a car merely slowing down in traffic. Uses the LEARNED
        # window once enough real gaps have been observed, not just the
        # fixed default.
        recently_parked_window, _, _ = self._learned_recently_parked_window_seconds()
        if (self._walking_last_genuine_park_ts is None
                or ts - self._walking_last_genuine_park_ts > recently_parked_window):
            self._walking_consecutive_pace_count = 0
            self._walking_last_genuine_park_ts = None  # this park event is over -- clear it so a genuinely new park can be detected next time
            return False

        if speed_kmh > threshold_kmh:
            # Real vehicle speed -- exit immediately, no pattern needed.
            # Also clears the park timestamp: genuinely driving again means
            # this park event is definitively over, not just paused.
            self._walking_consecutive_pace_count = 0
            self._walking_last_genuine_park_ts = None
            return False

        # In the walking-pace range, with a genuine recent park behind us
        # -- require a sustained pattern, not a single reading, before
        # actually flipping to walking.
        self._walking_consecutive_pace_count += 1
        is_walking = self._walking_consecutive_pace_count >= WALKING_PATTERN_CONSECUTIVE_READINGS
        if is_walking:
            self._record_walking_speed_sample(speed_kmh)
            # Record the park-to-walk gap exactly once per park event --
            # right when walking is FIRST confirmed for this park, not on
            # every subsequent tick while still walking.
            if not self._walking_gap_recorded_for_current_park:
                self._record_park_to_walk_gap_sample(ts - self._walking_last_genuine_park_ts, nearest.get("address"))
                self._walking_gap_recorded_for_current_park = True
                cursor = self.db.conn.execute(
                    "UPDATE trips SET walking_confirmed_ts = ? WHERE end_time IS NULL AND walking_confirmed_ts IS NULL",
                    (ts,),
                )
                self.db.conn.commit()
                # GAP 3 (diagnostic-coverage pass): previously silent.
                if cursor.rowcount > 0:
                    self._last_phase_capture_log = f"Captured walking_confirmed_ts = {ts}"
        return is_walking

    def _evaluate_trip_end(self, speed_kmh, ts):
        if speed_kmh < TRIP_STOP_SPEED_KMH:
            if self._below_stop_speed_since is None:
                self._below_stop_speed_since = ts
        else:
            self._below_stop_speed_since = None

        parked_long_enough = (
            self._below_stop_speed_since is not None
            and ts - self._below_stop_speed_since >= TRIP_END_PARK_SECONDS
        )
        if not parked_long_enough:
            return None

        # In DASHER mode, don't end the trip while a delivery stop is still
        # pending (e.g. parked briefly at a red light mid-route), or while a
        # pickup is actively in progress (e.g. waiting 8+ minutes at the
        # restaurant for the order -- which would otherwise look exactly
        # like "parked long enough, end the trip" from GPS alone). In
        # GENERAL mode -- or a DASHER trip where every stop is matched and
        # no pickup is pending -- parking alone is enough to end the trip.
        has_pending_stop = self.stops and not all(s["matched"] for s in self.stops)
        has_active_pickup = self.pickup is not None and not self.pickup.get("recorded")
        if self._trip_mode == "DASHER" and (has_pending_stop or has_active_pickup):
            return None

        return self._end_trip(ts)

    def _end_trip(self, ts):
        summary = self._compute_summary(ts)
        delivery_speed_event = self._persist_trip(summary)
        self._merge_accel_samples_into_history()
        self.state = self.STATE_IDLE
        self._above_start_speed_since = None
        self._below_stop_speed_since = None
        self._parked_since = None
        self._delay_logged_for_current_park = False
        return delivery_speed_event

    def force_end_trip(self):
        """
        Explicitly ends the current trip right now, regardless of speed --
        used when monitoring is manually stopped mid-drive.

        REAL BUG THIS FIXES, confirmed via a real diagnostic log: trips
        previously only ever ended through _evaluate_trip_end, which
        requires sustained near-zero speed for TRIP_END_PARK_SECONDS. If
        "Stop Monitoring" was tapped while a trip was still genuinely
        active (not yet parked long enough, or moving at all), the trip
        was simply abandoned -- stopTracking() kills the GPS feed
        entirely, so no further on_gps_update calls could ever complete
        the normal end sequence. The trip's row stayed in the database
        forever with end_time still NULL, invisible everywhere (Trip
        History, CSV, Full Report all filter to end_time IS NOT NULL) --
        not because data was lost, but because nothing ever finalized it.
        Previously the only thing that ever caught orphaned trips like
        this was _recover_interrupted_trips, which only runs once per
        app PROCESS start -- so if the same process kept running across
        multiple Start/Stop cycles without a restart, the trip stayed
        invisible indefinitely, not just until the next launch.

        This is NOT flagged was_interrupted -- that flag means something
        went wrong unexpectedly (a crash); deliberately stopping
        monitoring is a normal, intentional way for a trip to end, and
        the data collected up to this point is entirely legitimate.
        """
        if self.state != self.STATE_ACTIVE:
            return None
        return self._end_trip(time.time())

    # -- scoring / persistence ------------------------------------------
    def _compute_summary(self, end_ts):
        moving = sum(1 for p in self.gps_points if p[4] == "moving")
        slow = sum(1 for p in self.gps_points if p[4] == "slow")
        stopped = sum(1 for p in self.gps_points if p[4] == "stopped")
        total = max(1, moving + slow + stopped)

        distance_km = self._estimate_distance_km()
        time_eff_score = round((moving / total) * 100.0, 1)
        safety_score = self._safety_score(distance_km)
        geofence_ratio = self._geofence_hit_ratio()
        composite = round(0.4 * time_eff_score + 0.3 * safety_score + 0.3 * geofence_ratio, 1)
        fuel_cost = round(distance_km * 0.12, 2)  # simple $/km estimate

        return {
            "start_time": self.gps_points[0][3] if self.gps_points else end_ts,
            "end_time": end_ts,
            "distance_km": round(distance_km, 2),
            "moving_seconds": moving,
            "slow_seconds": slow,
            "stopped_seconds": stopped,
            "time_efficiency_score": time_eff_score,
            "safety_score": safety_score,
            "geofence_hit_ratio": geofence_ratio,
            "composite_score": composite,
            "fuel_cost_estimate": fuel_cost,
        }

    def _estimate_distance_km(self):
        return self._cumulative_distance_km

    def _safety_score(self, distance_km):
        if distance_km <= 0:
            return 100.0
        events_per_km = len(self.events) / distance_km
        return round(max(0.0, 100.0 - events_per_km * 15.0), 1)

    def _geofence_hit_ratio(self):
        if not self.stops:
            return 100.0
        matched = sum(1 for s in self.stops if s["matched"])
        return round((matched / len(self.stops)) * 100.0, 1)

    def _persist_trip(self, summary):
        if self.trip_id is None:
            return None
        c = self.db.conn
        c.execute("""
            UPDATE trips SET end_time=?, distance_km=?, moving_seconds=?,
                slow_seconds=?, stopped_seconds=?, time_efficiency_score=?,
                safety_score=?, geofence_hit_ratio=?, composite_score=?,
                fuel_cost_estimate=?, gps_points_json=?, mode=?
            WHERE id=?
        """, (
            summary["end_time"], summary["distance_km"], summary["moving_seconds"],
            summary["slow_seconds"], summary["stopped_seconds"],
            summary["time_efficiency_score"], summary["safety_score"],
            summary["geofence_hit_ratio"], summary["composite_score"],
            summary["fuel_cost_estimate"],
            json.dumps([[p[0], p[1], p[2], p[3]] for p in self.gps_points]),
            self._trip_mode,
            self.trip_id,
        ))
        for stop in self.stops:
            c.execute("""
                INSERT INTO stops (trip_id, address, lat, lon, matched, arrival_time)
                VALUES (?, ?, ?, ?, ?, ?)
            """, (self.trip_id, stop.get("address", ""), stop["lat"], stop["lon"],
                  int(stop["matched"]), stop["arrival_time"]))
        for e in self.events:
            c.execute("""
                INSERT INTO events (trip_id, event_type, lat, lon, timestamp, magnitude)
                VALUES (?, ?, ?, ?, ?, ?)
            """, (self.trip_id, e["event_type"], e["lat"], e["lon"], e["timestamp"], e["magnitude"]))
        for d in self.delays:
            c.execute("""
                INSERT INTO delays (trip_id, lat, lon, duration_seconds, timestamp)
                VALUES (?, ?, ?, ?, ?)
            """, (self.trip_id, d["lat"], d["lon"], d["duration_seconds"], d["timestamp"]))
        for m in self.messages:
            c.execute("""
                INSERT INTO messages (trip_id, sender, body, timestamp, extracted_instruction)
                VALUES (?, ?, ?, ?, ?)
            """, (self.trip_id, m["sender"], m["body"], m["timestamp"], m["extracted_instruction"]))

        delivery_speed_event = self._persist_distance_accuracy(summary)
        c.commit()
        return delivery_speed_event

    def _persist_distance_accuracy(self, summary):
        """
        Two independent things, both needing pickup arrival/departure data
        (not needing each other):

        1. Delivery speed: distance/time of the pickup-departure -> trip-end
           leg, returned so the caller can feed SmartScoreEngine.
           record_delivery_speed() -- this only needs departure tracking,
           not a claimed offer distance.
        2. Offer distance accuracy comparison: needs BOTH departure tracking
           AND a claimed offer distance to compare against. Empirically
           answers "does the offer's distance figure include the drive to
           the restaurant, or just the delivery leg?" from this driver's
           own real data, rather than guessing or relying on unofficial/
           unconfirmed sources.
        """
        if self._distance_at_departure_km is None or self._departure_timestamp is None:
            return None  # pickup was registered but departure never completed this trip

        actual_total_km = self._cumulative_distance_km
        actual_delivery_km = actual_total_km - self._distance_at_departure_km

        delivery_speed_event = None
        delivery_time_hours = (summary["end_time"] - self._departure_timestamp) / 3600.0
        if delivery_time_hours > 0 and actual_delivery_km > 0:
            delivery_speed_event = {
                "distance_km": actual_delivery_km,
                "time_hours": delivery_time_hours,
            }

        if self.pickup and self.pickup.get("claimed_distance_km") is not None                 and self._deadhead_distance_km is not None:
            actual_deadhead_km = self._deadhead_distance_km
            self.db.conn.execute("""
                INSERT INTO offer_distance_accuracy
                    (trip_id, restaurant_name, claimed_distance_km, actual_deadhead_km,
                     actual_delivery_km, actual_total_km, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """, (
                self.trip_id, self.pickup["restaurant_name"],
                self.pickup["claimed_distance_km"],
                round(actual_deadhead_km, 3), round(actual_delivery_km, 3),
                round(actual_total_km, 3), summary["end_time"],
            ))

        return delivery_speed_event


# ------------------------------------------------------------------------- #
# Top-level engine facade (what Chaquopy/Java talks to)
# ------------------------------------------------------------------------- #
class DriveMonitorEngine:
    def __init__(self, files_dir):
        self.files_dir = files_dir
        db_path = os.path.join(files_dir, "drive_monitor.db")
        self._db_path = db_path
        self.db = Database(db_path)
        self.trip_manager = TripManager(self.db)
        self.smart_score = SmartScoreEngine(self.db)
        self.stops_buffer = StopsBuffer()
        self.trusted_contacts = TrustedContacts(self.db)
        self._recover_interrupted_trips()
        self._recover_abandoned_offers()

    def _recover_interrupted_trips(self):
        """
        Finds any trip left with end_time IS NULL from a previous session
        -- meaning the app was killed/crashed mid-trip before it could end
        properly (see _persist_trip). Without this, such a trip becomes a
        permanently orphaned row: invisible to Trip History, CSV export,
        and every learning query, forever, since all of those filter by
        "WHERE end_time IS NOT NULL".

        HONEST LIMIT, UPDATED: this used to be a much harder limit -- before
        _maybe_save_partial_progress existed, distance/safety/efficiency
        were ONLY ever computed from in-memory state at proper trip end,
        so a crash lost all of it and this method could only zero
        everything out. Now that TripManager periodically saves real
        progress during the trip (at most _PARTIAL_SAVE_INTERVAL_SECONDS
        stale), this method must NOT overwrite that real data -- it only
        sets end_time and was_interrupted, leaving whatever was already
        incrementally saved exactly as it was. A trip that crashed right
        at the start (before any periodic save ever ran) will still show
        zeros/nulls, honestly, since there was genuinely nothing to save
        yet -- but anything that ran long enough for at least one partial
        save now survives a crash with real, not fabricated, numbers.
        """
        orphaned = self.db.conn.execute(
            "SELECT id, start_time FROM trips WHERE end_time IS NULL"
        ).fetchall()
        for row in orphaned:
            # No better end-time signal is available -- use start_time as
            # a floor. Deliberately does NOT touch distance_km/moving_
            # seconds/etc. here: whatever _maybe_save_partial_progress
            # already wrote (or the real column defaults, if none ever
            # ran) is left exactly as-is, not zeroed out.
            self.db.conn.execute(
                "UPDATE trips SET end_time = COALESCE(end_time, ?), was_interrupted = 1 WHERE id = ?",
                (row["start_time"], row["id"])
            )
        if orphaned:
            self.db.conn.commit()

    # Trip lifecycle -------------------------------------------------
    def on_gps_update(self, lat, lon, speed_kmh, timestamp_ms):
        pickup_wait_event, delivery_speed_event = self.trip_manager.on_gps_update(
            lat, lon, speed_kmh, timestamp_ms
        )
        if pickup_wait_event:
            self.smart_score.record_restaurant_wait(
                pickup_wait_event["restaurant_name"], pickup_wait_event["wait_minutes"]
            )
        if delivery_speed_event:
            self.smart_score.record_delivery_speed(
                delivery_speed_event["distance_km"], delivery_speed_event["time_hours"]
            )
        arrival = self.trip_manager.take_pending_arrival()

        # Powers the RoadWarrior quick-navigation icon: appears while
        # approaching an unmatched stop (see APPROACHING_RADIUS_METERS),
        # disappears once that stop is matched (arrival detected) or no
        # longer exists. Independent of the arrival dialog/TTS above --
        # this is a heads-up WHILE STILL DRIVING, not an arrival event.
        approaching = self.trip_manager._check_approaching_stop(lat, lon)
        approaching_stop = None
        if approaching:
            approaching_stop = {
                "address": approaching.get("address", ""),
                "lat": approaching["lat"],
                "lon": approaching["lon"],
            }

        # Same idea, for the pickup side -- previously had no equivalent at
        # all. "address" can be None even while approaching (still waiting
        # on the separate formatted-address geocode) -- Java surfaces that
        # as a "waiting for pickup address" state rather than hiding the
        # icon entirely, since the restaurant name/coordinates are already
        # known and useful on their own.
        approaching_pickup = self.trip_manager.check_approaching_pickup(lat, lon)

        # Feeds the persistent, tappable instruction overlay -- shown
        # while APPROACHING a stop (not waiting for arrival), and
        # deliberately does NOT auto-clear even after arrival, since the
        # delivery may not actually be complete yet. Only clears when
        # manually tapped away.
        self.trip_manager._check_approach_instruction(approaching, timestamp_ms / 1000.0)
        approach_instruction = self.trip_manager.take_pending_approach_instruction()

        # Powers the purple "walking" status dot -- DASHER mode only, and
        # only in the window between "nearly at the stop" and "confirmed
        # arrived" (see TripManager.is_walking_pace). Passes the real GPS
        # timestamp through -- needed for tracking how long a genuine
        # park has lasted, not just the current instant's speed.
        is_walking = self.trip_manager.is_walking_pace(lat, lon, speed_kmh, timestamp_ms / 1000.0)

        # Previously zero trace of the park-to-walk gap learning ever
        # running -- consumed here (TripManager has no direct access to
        # log_diagnostic itself) and surfaced for Java to log.
        gap_sample_log = self.trip_manager._last_gap_sample_log
        self.trip_manager._last_gap_sample_log = None

        # GAP 3 (diagnostic-coverage pass): same consumption pattern for
        # the phase-timestamp captures -- previously completely silent.
        phase_capture_log = self.trip_manager._last_phase_capture_log
        self.trip_manager._last_phase_capture_log = None

        return json.dumps({
            "state": self.trip_manager.state,
            "mode": self.trip_manager.get_mode(),
            "arrival": arrival,
            "approaching_stop": approaching_stop,
            "approaching_pickup": approaching_pickup,
            "approach_instruction": approach_instruction,
            "is_walking": is_walking,
            "gap_sample_log": gap_sample_log,
            "phase_capture_log": phase_capture_log,
        })

    def is_walking_pace(self, lat, lon, speed_kmh, ts=None):
        """Wrapper -- see TripManager.is_walking_pace for the actual logic."""
        return self.trip_manager.is_walking_pace(lat, lon, speed_kmh, ts)

    # ------------------------------------------------------------------ #
    # Durable offer-outcome recovery. Fixes a real, confirmed bug: the
    # in-memory grace-period mechanism (Java's Handler.postDelayed, used
    # to let a real Accept/Decline tap win a race against a timeout) is
    # NOT durable -- if the app process crashes at any point before that
    # delayed callback fires, the pending timeout is lost forever with
    # the dead process, and the offer's outcome never gets recorded at
    # all. Confirmed via a real diagnostic log: an offer was detected,
    # the process then went silent and restarted almost exactly when the
    # offer's real countdown would have expired, and no OUTCOME entry
    # was ever recorded for it.
    #
    # The fix mirrors _recover_interrupted_trips's proven pattern: persist
    # the offer's details to the DATABASE (durable, survives a crash) the
    # moment it's detected, and check for an unresolved one on the NEXT
    # engine startup -- recovering exactly the scenario the log showed.
    # ------------------------------------------------------------------ #

    def save_pending_offer_for_recovery(self, restaurant_name, payout, distance_km,
                                         smart_score, components_json, countdown_seconds=None):
        """
        Called the moment an offer is detected -- BEFORE the in-memory
        grace-period logic runs, so this durable record exists regardless
        of whether the process survives long enough for that to matter.
        expires_ts uses the real countdown if one was found on screen
        (best-effort, not confirmed against a real sample -- see
        OfferScreenParser.extract_countdown_seconds), falling back to a
        conservative generic assumption otherwise.
        """
        now = time.time()
        if countdown_seconds is not None:
            expires_ts = now + countdown_seconds + 10  # +10s buffer for screen-read/processing lag
        else:
            expires_ts = now + DEFAULT_OFFER_TIMEOUT_ASSUMPTION_SECONDS
        self.db.conn.execute("""
            INSERT INTO pending_offer_recovery
                (id, restaurant_name, payout, distance_km, smart_score, components_json, detected_ts, expires_ts)
            VALUES (1, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                restaurant_name = excluded.restaurant_name, payout = excluded.payout,
                distance_km = excluded.distance_km, smart_score = excluded.smart_score,
                components_json = excluded.components_json, detected_ts = excluded.detected_ts,
                expires_ts = excluded.expires_ts
        """, (restaurant_name, payout, distance_km, smart_score, components_json, now, expires_ts))
        self.db.conn.commit()

    def clear_pending_offer_recovery(self):
        """
        Called whenever an offer gets properly resolved through the
        normal in-memory path (accepted, declined, or a timeout that
        successfully completed its grace period) -- prevents the SAME
        offer from later being double-counted by the recovery check on a
        future restart.
        """
        self.db.conn.execute("DELETE FROM pending_offer_recovery WHERE id = 1")
        self.db.conn.commit()

    def _recover_abandoned_offers(self):
        """
        Run once at engine startup, mirroring _recover_interrupted_trips.
        If a pending offer record exists AND its expiration has genuinely
        passed, SOMETHING happened to it while the process was dead --
        but confirmed via a real incident (a completed McDonald's
        delivery that the process was dead for the entire duration of,
        then got labeled "timed out" on recovery, corrupting real
        acceptance stats and Personal Calibration with a false data
        point): a crash-recovered offer is NOT the same as a confirmed
        timeout. You may well have accepted it and driven the entire
        real delivery while the process was dead. Recorded as an honest
        "outcome_unknown" instead of a confident but potentially false
        "timed_out" -- see get_acceptance_stats and
        recalculate_personal_calibration for how this gets excluded from
        stats rather than silently corrupting them.
        """
        row = self.db.conn.execute("SELECT * FROM pending_offer_recovery WHERE id = 1").fetchone()
        if row is None:
            return
        if time.time() >= row["expires_ts"]:
            self.db.conn.execute("""
                INSERT INTO offer_outcomes
                    (restaurant_name, payout, distance_km, smart_score, accepted, outcome, timestamp, components_json, is_test_data)
                VALUES (?, ?, ?, ?, 0, 'outcome_unknown', ?, ?, 0)
            """, (row["restaurant_name"], row["payout"], row["distance_km"], row["smart_score"],
                  time.time(), row["components_json"]))
            self.db.conn.commit()
            self.log_diagnostic("OUTCOME", "Recovered an offer abandoned by a crash/restart -- outcome UNKNOWN "
                f"(the process was dead the whole time; this may have been a real completed delivery, not a "
                f"timeout): {row['restaurant_name']} (detected {time.time() - row['detected_ts']:.0f}s before recovery)")
            self.clear_pending_offer_recovery()
        # If expiration hasn't passed yet, leave it in place -- still
        # genuinely pending, not yet safe to assume anything happened to it.

    def record_parking_difficulty_feedback(self, restaurant_name, gap_seconds, difficulty):
        """
        Confirms or corrects what a raw park-to-walk duration actually
        meant -- a long gap alone can't distinguish a genuinely difficult
        park from an easy one that's just naturally far from the door.
        difficulty: 'easy', 'normal', or 'difficult'.
        """
        self.db.conn.execute("""
            INSERT INTO parking_difficulty_feedback (restaurant_name, gap_seconds, difficulty, timestamp)
            VALUES (?, ?, ?, ?)
        """, (restaurant_name, gap_seconds, difficulty, time.time()))
        self.db.conn.commit()

    def record_pickup_location(self, restaurant_name, lat, lon):
        """
        Starts persisting real, geocoded pickup coordinates going
        forward -- this is genuinely new; pickup locations were
        previously only ever used transiently for the current offer's
        traffic calculation, then discarded, with no historical record
        kept anywhere. Needed before a real merchant "sweet spot" can
        ever be suggested -- can't be backfilled from anything recorded
        before this existed.
        """
        if lat == 0.0 and lon == 0.0:
            return  # placeholder coordinates (no API key configured) -- not real data worth keeping
        self.db.conn.execute("""
            INSERT INTO pickup_location_history (restaurant_name, lat, lon, timestamp)
            VALUES (?, ?, ?, ?)
        """, (restaurant_name, lat, lon, time.time()))
        self.db.conn.commit()

    def get_pickup_sweet_spot_zone(self):
        """
        Finds your real, most-frequent pickup zone -- reuses the same
        ~1.1km grid rounding already proven in zone-based traffic-risk
        learning, rather than a raw geographic average of all pickup
        locations (which could suggest a nonsensical midpoint if your
        real pickups are spread out and not actually clustered anywhere
        in particular). Gated on PICKUP_SWEET_SPOT_MIN_SAMPLES -- with
        less real history than that, there's genuinely not enough
        evidence yet to suggest anywhere with any confidence.
        """
        rows = self.db.conn.execute(
            "SELECT lat, lon FROM pickup_location_history"
        ).fetchall()
        if len(rows) < PICKUP_SWEET_SPOT_MIN_SAMPLES:
            return json.dumps({
                "has_suggestion": False, "sample_count": len(rows),
                "min_required": PICKUP_SWEET_SPOT_MIN_SAMPLES,
            })

        zone_counts = {}
        zone_coords = {}
        for row in rows:
            zone_lat = round(row["lat"], PICKUP_SWEET_SPOT_GRID_DECIMALS)
            zone_lon = round(row["lon"], PICKUP_SWEET_SPOT_GRID_DECIMALS)
            zone_key = (zone_lat, zone_lon)
            zone_counts[zone_key] = zone_counts.get(zone_key, 0) + 1
            zone_coords.setdefault(zone_key, []).append((row["lat"], row["lon"]))

        best_zone = max(zone_counts, key=zone_counts.get)
        best_count = zone_counts[best_zone]
        # Real average within the winning zone, not just its rounded
        # corner -- a slightly more precise "center of mass" to suggest.
        coords_in_zone = zone_coords[best_zone]
        avg_lat = sum(c[0] for c in coords_in_zone) / len(coords_in_zone)
        avg_lon = sum(c[1] for c in coords_in_zone) / len(coords_in_zone)

        return json.dumps({
            "has_suggestion": True,
            "lat": round(avg_lat, 5), "lon": round(avg_lon, 5),
            "zone_sample_count": best_count, "total_sample_count": len(rows),
            "pct_of_total": round(100.0 * best_count / len(rows), 1),
        })

    def check_show_return_to_sweet_spot(self, lat, lon):
        """
        Called once ALL deliveries in a trip are complete (the trip state
        transitioning back to IDLE), not per-stop within a batch. Only
        suggests returning to the sweet spot if you're currently beyond
        UNFAMILIAR_AREA_THRESHOLD_KM from it -- no point suggesting you
        navigate somewhere you're already right next to. Also honestly
        reports when there's no real sweet-spot suggestion yet at all
        (not enough pickup history), rather than showing nothing with no
        explanation.
        """
        sweet_spot = json.loads(self.get_pickup_sweet_spot_zone())
        if not sweet_spot.get("has_suggestion"):
            return json.dumps({"should_show": False, "reason": "no_sweet_spot_yet"})
        distance_km = haversine_meters(lat, lon, sweet_spot["lat"], sweet_spot["lon"]) / 1000.0
        if distance_km < UNFAMILIAR_AREA_THRESHOLD_KM:
            return json.dumps({"should_show": False, "reason": "already_close", "distance_km": round(distance_km, 2)})
        return json.dumps({
            "should_show": True, "distance_km": round(distance_km, 2),
            "lat": sweet_spot["lat"], "lon": sweet_spot["lon"],
        })

    def get_parking_difficulty_rating(self, restaurant_name):
        """
        Returns this restaurant's learned parking-difficulty rating,
        gated on PARKING_DIFFICULTY_MIN_SAMPLES confirmed samples for
        THIS specific restaurant -- same mapping pattern as personal
        calibration's overall_rating_map: easy=0, normal=50, difficult=100,
        averaged across every confirmation given for this location.
        """
        difficulty_map = {"easy": 0.0, "normal": 50.0, "difficult": 100.0}
        rows = self.db.conn.execute(
            "SELECT difficulty FROM parking_difficulty_feedback WHERE restaurant_name = ?",
            (restaurant_name,),
        ).fetchall()
        scores = [difficulty_map[r["difficulty"]] for r in rows if r["difficulty"] in difficulty_map]
        if len(scores) < PARKING_DIFFICULTY_MIN_SAMPLES:
            return json.dumps({
                "has_rating": False, "sample_count": len(scores),
                "min_required": PARKING_DIFFICULTY_MIN_SAMPLES,
            })
        avg_score = sum(scores) / len(scores)
        label = "Easy" if avg_score < 33 else "Difficult" if avg_score > 66 else "Normal"
        return json.dumps({
            "has_rating": True, "sample_count": len(scores),
            "avg_score": round(avg_score, 1), "label": label,
        })

    def get_canned_replies_json(self):
        """Returns all canned replies, in their user-defined order."""
        rows = self.db.conn.execute(
            "SELECT id, text FROM canned_replies ORDER BY sort_order"
        ).fetchall()
        return json.dumps([{"id": r["id"], "text": r["text"]} for r in rows])

    def add_canned_reply(self, text):
        """Appends a new reply to the end of the list."""
        row = self.db.conn.execute(
            "SELECT MAX(sort_order) AS max_order FROM canned_replies"
        ).fetchone()
        next_order = (row["max_order"] + 1) if row["max_order"] is not None else 0
        self.db.conn.execute(
            "INSERT INTO canned_replies (text, sort_order) VALUES (?, ?)", (text, next_order)
        )
        self.db.conn.commit()

    def update_canned_reply(self, reply_id, text):
        self.db.conn.execute(
            "UPDATE canned_replies SET text = ? WHERE id = ?", (text, reply_id)
        )
        self.db.conn.commit()

    def delete_canned_reply(self, reply_id):
        self.db.conn.execute("DELETE FROM canned_replies WHERE id = ?", (reply_id,))
        self.db.conn.commit()

    def get_last_parking_gap_for_feedback(self):
        """Wrapper -- see TripManager.get_last_parking_gap_for_feedback for the actual logic."""
        result = self.trip_manager.get_last_parking_gap_for_feedback()
        return json.dumps(result) if result is not None else json.dumps(None)

    def clear_last_parking_gap_for_feedback(self):
        """Wrapper -- see TripManager.clear_last_parking_gap_for_feedback for the actual logic."""
        self.trip_manager.clear_last_parking_gap_for_feedback()

    def get_database_file_path(self):
        """The real on-disk path to the SQLite database file -- needed by
        Java so it knows exactly what file to replace during a restore."""
        return self._db_path

    def backup_database_to(self, dest_path):
        """
        Writes a SAFE, consistent snapshot of the entire database to
        dest_path -- uses SQLite's own online-backup API rather than a
        raw file copy, since a raw copy could catch a transaction
        mid-write and produce a corrupted snapshot. This covers every
        table: all trip history, safety events, restaurant wait/deadhead/
        delivery-speed/walking-speed learning, personal calibration,
        offer outcomes, everything -- not just one table like the CSV
        export.
        """
        dest_conn = sqlite3.connect(dest_path)
        try:
            self.db.conn.backup(dest_conn)
        finally:
            dest_conn.close()
        return True

    def close_database_for_restore(self):
        """
        Closes the live database connection cleanly -- MUST be called
        before the actual database file on disk is replaced with a
        restored backup. Replacing the file underneath a still-open
        connection risks corruption; this is why a restore also requires
        a full app restart afterward rather than trying to hot-swap the
        connection in place.
        """
        self.db.conn.close()

    def get_state(self):
        return self.trip_manager.state

    def force_end_trip(self):
        """Wrapper -- see TripManager.force_end_trip for the actual logic."""
        return self.trip_manager.force_end_trip()

    def get_mode(self):
        return self.trip_manager.get_mode()

    def set_dasher_foreground(self, is_foreground):
        """Called from DasherAccessibilityService based on which app
        currently has focus -- drives DASHER vs GENERAL mode detection."""
        self.trip_manager.set_dasher_foreground(is_foreground)

    def get_last_trip_summary(self):
        """
        Post-trip summary: most recently completed trip's scores, every
        customer instruction, safety events, and major delays captured
        during it. Called from MainActivity's "View Last Trip Summary"
        button.
        """
        row = self.db.conn.execute(
            "SELECT * FROM trips WHERE end_time IS NOT NULL ORDER BY id DESC LIMIT 1"
        ).fetchone()
        if not row:
            return json.dumps({"found": False})
        return json.dumps(self._build_trip_summary_dict(row))

    def get_trip_summary_by_id(self, trip_id):
        """
        Same as get_last_trip_summary() but for an arbitrary past trip --
        called when a specific trip is tapped in "View Trip History".
        """
        row = self.db.conn.execute(
            "SELECT * FROM trips WHERE id = ? AND end_time IS NOT NULL", (trip_id,)
        ).fetchone()
        if not row:
            return json.dumps({"found": False})
        return json.dumps(self._build_trip_summary_dict(row))

    def get_trip_history(self, limit=20):
        """
        Lists the most recent completed trips (newest first) for "View
        Trip History" -- previously only the single most recent trip was
        ever viewable; older trips were recorded but had no way to be
        browsed.
        """
        rows = self.db.conn.execute(
            "SELECT id, start_time, mode, distance_km, composite_score "
            "FROM trips WHERE end_time IS NOT NULL ORDER BY id DESC LIMIT ?",
            (limit,),
        ).fetchall()
        return json.dumps({
            "trips": [
                {
                    "trip_id": r["id"],
                    "start_time": r["start_time"],
                    "mode": r["mode"],
                    "distance_km": r["distance_km"],
                    "composite_score": r["composite_score"],
                }
                for r in rows
            ]
        })

    def save_trip_feedback(self, trip_id, rating, notes, parking_rating=None,
                            navigation_rating=None, merchant_wait_rating=None,
                            customer_rating=None, overall_rating=None):
        """
        Records your own rating (1-5) and optional notes for a completed
        trip -- "was this actually a good delivery, in your judgment"
        rather than just what the Smart Score predicted. Overwrites any
        previous feedback for the same trip if called again.

        The five quick-tap category fields (parking/navigation/merchant
        wait/customer/overall) are optional, one-word ratings requiring no
        typing -- e.g. parking_rating="Hard", customer_rating="Rude".
        All default to None (skippable) since forcing every category on
        every trip would be more friction than the data is worth.

        NOTE ON SCOPE: this collects the data, which recalculate_personal_
        calibration (see below, called right after this by the caller)
        DOES feed back into scoring automatically via
        SmartScoreEngine._get_calibrated_weights -- bounded, and gated on
        a minimum sample count so a handful of early ratings can't swing
        future weights too far. (Previously this docstring said that
        feedback loop wasn't built yet -- it was, but unreachable from a
        real feedback submission due to a missing wrapper method, see
        recalculate_personal_calibration's own comment.)
        """
        self.db.conn.execute("""
            INSERT INTO trip_feedback
                (trip_id, rating, notes, parking_rating, navigation_rating,
                 merchant_wait_rating, customer_rating, overall_rating, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(trip_id) DO UPDATE SET
                rating = excluded.rating, notes = excluded.notes,
                parking_rating = excluded.parking_rating,
                navigation_rating = excluded.navigation_rating,
                merchant_wait_rating = excluded.merchant_wait_rating,
                customer_rating = excluded.customer_rating,
                overall_rating = excluded.overall_rating,
                timestamp = excluded.timestamp
        """, (trip_id, rating, notes, parking_rating, navigation_rating,
              merchant_wait_rating, customer_rating, overall_rating, time.time()))
        self.db.conn.commit()

    def recalculate_personal_calibration(self):
        """
        CRITICAL BUG FIX, same class as add_pickup's (see its own comment
        above): this wrapper was completely missing from DriveMonitorEngine
        -- only SmartScoreEngine had recalculate_personal_calibration.
        Every real call from Java (MainActivity's showFeedbackDialog, right
        after save_trip_feedback succeeds) threw AttributeError, caught by
        the surrounding try/catch, which then wrongly toasted "Could not
        save feedback" even though the rating HAD already been saved --
        and skipped the parking-difficulty-feedback and
        clear_last_parking_gap_for_feedback calls after it in the same try
        block, since Java aborts the rest of a try on an uncaught throw.
        Net effect: the whole personal-calibration learning loop this
        method exists for has likely never actually run from a real
        feedback submission, despite testing correctly against
        SmartScoreEngine directly. Found by tracing the real call path
        instead of assuming it worked because the underlying logic did.
        """
        return self.smart_score.recalculate_personal_calibration()

    def get_last_parking_gap_for_feedback(self):
        """
        Same missing-wrapper bug as recalculate_personal_calibration above
        -- only TripManager had this. Its caller (showFeedbackDialog)
        already guards this specific call in its own try/catch and falls
        back to a plain "Parking" label, so this failure was silent rather
        than misleading -- but it meant the measured-park-to-walk-duration
        context label never actually showed, and finalPendingParkingRestaurant
        stayed null every time, so record_parking_difficulty_feedback was
        never reachable either.
        """
        return self.trip_manager.get_last_parking_gap_for_feedback()

    def clear_last_parking_gap_for_feedback(self):
        """Same missing-wrapper bug as the two methods above."""
        return self.trip_manager.clear_last_parking_gap_for_feedback()

    def get_feedback_summary(self):
        """
        Aggregate view of your own ratings vs. what the Smart Score
        predicted for the same trips -- the first step toward answering
        "does the score actually track what I think is a good delivery."
        UPDATED: recalculate_personal_calibration (see above, now fixed)
        DOES adjust the weights SmartScoreEngine.calculate() actually uses,
        via _get_calibrated_weights -- this docstring previously called
        that "a separate, not-yet-built step," which was accidentally true
        in practice (the wrapper bug above meant it silently never ran),
        but wrong about intent: the mechanism was already built, just
        unreachable from a real feedback submission until now.
        """
        rows = self.db.conn.execute("""
            SELECT tf.rating, t.composite_score
            FROM trip_feedback tf
            JOIN trips t ON t.id = tf.trip_id
            WHERE tf.rating IS NOT NULL
        """).fetchall()
        if not rows:
            return json.dumps({"sample_count": 0})

        avg_rating = sum(r["rating"] for r in rows) / len(rows)
        avg_score = sum(r["composite_score"] or 0 for r in rows) / len(rows)
        return json.dumps({
            "sample_count": len(rows),
            "avg_rating": round(avg_rating, 2),
            "avg_composite_score": round(avg_score, 1),
        })

    def record_offer_outcome(self, restaurant_name, payout, distance_km, smart_score, accepted,
                              components_json=None, is_test_data=False):
        """
        Records whether a scored offer was actually accepted or declined --
        detected via a real Accept/Decline button tap (see
        DasherAccessibilityService's typeViewClicked handling), not
        inferred.

        components_json: the offer's full 6-factor breakdown, stored so
        DECLINED offers can also feed personal calibration (see
        recalculate_personal_calibration) -- not just accepted offers that
        went on to become a rated trip. A decline is itself a real signal:
        whatever factor made the score look good but didn't look good
        enough to actually accept is exactly the kind of mismatch
        calibration is meant to catch.

        is_test_data: True only when called from Developer Testing's
        "Simulate Offer Outcomes" button -- excluded from every report and
        stat (see get_acceptance_stats, get_rejected_offers_report,
        export_full_report, recalculate_personal_calibration), since a
        simulated test isn't a real decision and shouldn't pollute real
        stats.
        """
        outcome = "accepted" if accepted else "declined"
        self.db.conn.execute("""
            INSERT INTO offer_outcomes
                (restaurant_name, payout, distance_km, smart_score, accepted, outcome, timestamp, components_json, is_test_data)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (restaurant_name, payout, distance_km, smart_score, int(accepted), outcome, time.time(),
              components_json, int(is_test_data)))
        self.db.conn.commit()

    def record_offer_timeout(self, restaurant_name, payout, distance_km, smart_score,
                              components_json=None, is_test_data=False):
        """
        Records that a scored offer disappeared from screen without either
        button being tapped -- previously invisible entirely: only Accept/
        Decline taps generated any record, so a timed-out offer was
        neither counted as accepted, declined, nor anything else. It's
        recorded distinctly as "timed_out" rather than folded into
        "declined", since choosing not to act and the countdown expiring
        are genuinely different things -- and deliberately excluded from
        calibration for the same reason (a timeout isn't a real
        preference signal the way an active decline is).

        HONEST NOTE: "timed out" is really "disappeared without a
        detected tap" -- this includes the countdown genuinely expiring,
        but could also include the offer being intercepted by another
        Dasher, or some other reason the screen changed away. Not
        perfectly distinguishable from in here.

        is_test_data: see record_offer_outcome's note above.
        """
        self.db.conn.execute("""
            INSERT INTO offer_outcomes
                (restaurant_name, payout, distance_km, smart_score, accepted, outcome, timestamp, components_json, is_test_data)
            VALUES (?, ?, ?, ?, 0, 'timed_out', ?, ?, ?)
        """, (restaurant_name, payout, distance_km, smart_score, time.time(), components_json, int(is_test_data)))
        self.db.conn.commit()

    def get_acceptance_stats(self):
        """
        Aggregate accept/decline/timeout stats -- acceptance rate, and
        average Smart Score for each outcome (a sanity check on whether
        the score actually tracks what you choose to accept). Excludes
        test data recorded via Developer Testing's simulate button (see
        record_offer_outcome's is_test_data note).

        Also excludes 'outcome_unknown' rows (crash-recovered offers
        where we genuinely don't know what happened -- see
        _recover_abandoned_offers) from the confident stats entirely --
        confirmed via a real incident that counting these as "timed_out"
        was actively wrong (a real, completed delivery got mislabeled),
        and even excluding them from "timed_out" specifically, still
        including them in the sample_count/acceptance_rate denominator
        would silently understate your real acceptance rate. Reported
        separately instead, for transparency about how much real data
        was lost to crashes without letting it corrupt the confident
        numbers.
        """
        rows = self.db.conn.execute(
            "SELECT smart_score, outcome, payout, distance_km FROM offer_outcomes "
            "WHERE is_test_data = 0 AND outcome != 'outcome_unknown'"
        ).fetchall()
        unknown_count = self.db.conn.execute(
            "SELECT COUNT(*) AS cnt FROM offer_outcomes WHERE is_test_data = 0 AND outcome = 'outcome_unknown'"
        ).fetchone()["cnt"]
        if not rows:
            return json.dumps({"sample_count": 0, "unknown_outcome_count": unknown_count})

        accepted_rows = [r for r in rows if r["outcome"] == "accepted"]
        declined_rows = [r for r in rows if r["outcome"] == "declined"]
        timed_out_rows = [r for r in rows if r["outcome"] == "timed_out"]

        def avg(values):
            values = [v for v in values if v is not None]
            return round(sum(values) / len(values), 1) if values else None

        return json.dumps({
            "sample_count": len(rows),
            "accepted_count": len(accepted_rows),
            "declined_count": len(declined_rows),
            "timed_out_count": len(timed_out_rows),
            "unknown_outcome_count": unknown_count,
            "acceptance_rate_pct": round(100.0 * len(accepted_rows) / len(rows), 1),
            "avg_score_accepted": avg([r["smart_score"] for r in accepted_rows]),
            "avg_score_declined": avg([r["smart_score"] for r in declined_rows]),
            "avg_score_timed_out": avg([r["smart_score"] for r in timed_out_rows]),
            "avg_payout_accepted": avg([r["payout"] for r in accepted_rows]),
        })

    def get_rejected_offers_report(self, limit=50):
        """
        Report card for offers you did NOT end up accepting -- both
        actively declined AND timed out (disappeared from screen with no
        tap detected). Previously this only showed active declines,
        which meant it stayed empty for anyone who mostly loses offers to
        inaction (the countdown expiring) rather than tapping Decline --
        a real, reported gap, not a hypothetical one.

        Each entry is clearly labeled with its actual outcome ("declined"
        vs "timed_out") so these aren't presented as if they were the
        same thing -- an active decline and losing an offer to inaction
        are genuinely different, even though both mean you didn't take it.

        NOTE ON SCOPE: this is a VIEWING report, not the calibration
        learning signal -- recalculate_personal_calibration still
        deliberately excludes timeouts from what it learns from (a
        timeout isn't as clean a preference signal as an active decline;
        you might have simply been busy or distracted). That exclusion is
        unchanged here -- this only affects what you can SEE, not what
        the algorithm learns from.
        """
        not_accepted = self.db.conn.execute("""
            SELECT restaurant_name, payout, distance_km, smart_score, components_json, timestamp, outcome
            FROM offer_outcomes WHERE outcome IN ('declined', 'timed_out') AND is_test_data = 0
            ORDER BY timestamp DESC LIMIT ?
        """, (limit,)).fetchall()

        entries = []
        for row in not_accepted:
            components = None
            if row["components_json"]:
                try:
                    components = json.loads(row["components_json"])
                except (ValueError, TypeError):
                    components = None
            entries.append({
                "restaurant_name": row["restaurant_name"],
                "payout": row["payout"],
                "distance_km": row["distance_km"],
                "smart_score": row["smart_score"],
                "components": components,
                "timestamp": row["timestamp"],
                "outcome": row["outcome"],
            })

        # Per-factor comparison: average component score for accepted vs
        # declined vs timed-out offers -- if a factor scores similarly
        # well across all three, it isn't what's actually driving whether
        # you end up taking an offer.
        all_rows = self.db.conn.execute(
            "SELECT outcome, components_json FROM offer_outcomes WHERE components_json IS NOT NULL AND is_test_data = 0"
        ).fetchall()
        factor_keys = ["base_score", "hourly_score", "deadhead_score", "wait_score", "time_score", "weather_score"]
        sums = {k: {"accepted": [], "declined": [], "timed_out": []} for k in factor_keys}
        for row in all_rows:
            if row["outcome"] not in ("accepted", "declined", "timed_out"):
                continue
            try:
                components = json.loads(row["components_json"])
            except (ValueError, TypeError):
                continue
            for k in factor_keys:
                if k in components:
                    sums[k][row["outcome"]].append(components[k])

        def avg(values):
            return round(sum(values) / len(values), 1) if values else None

        comparison = [{
            "factor": k,
            "avg_accepted": avg(sums[k]["accepted"]),
            "avg_declined": avg(sums[k]["declined"]),
            "avg_timed_out": avg(sums[k]["timed_out"]),
        } for k in factor_keys]

        return json.dumps({"entries": entries, "comparison": comparison})

    def export_trips_csv(self):
        """
        Returns CSV text (as a plain string -- Java writes it to an
        actual file and shares it) covering every completed trip.

        Previously missing geofence_hit_ratio, the was_interrupted flag,
        and the 5-category feedback breakdown (parking/navigation/
        merchant wait/customer/overall) -- only the plain rating+notes
        made it in. All included now.

        HONEST SCOPE NOTE: columns reflect what this app actually tracks,
        not an aspirational list. Per-trip earnings/accept-decline
        tracking doesn't exist (offers are scored before acceptance, but
        nothing currently links an accepted offer's payout to the trip
        that follows it), and dropoff suburb isn't geocoded -- so this
        doesn't include Amount, Suburb, or Accepted columns some
        delivery-tracking tools have. What's here is real, not invented.
        """
        rows = self.db.conn.execute("""
            SELECT id, start_time, end_time, mode, distance_km,
                   time_efficiency_score, safety_score, geofence_hit_ratio,
                   composite_score, fuel_cost_estimate, was_interrupted
            FROM trips WHERE end_time IS NOT NULL ORDER BY id ASC
        """).fetchall()

        lines = ["TripID,Date,Mode,DistanceKm,TimeEfficiency,SafetyScore,GeofenceHitRatio,"
                 "CompositeScore,FuelCostEstimate,Interrupted,YourRating,YourNotes,"
                 "ParkingRating,NavigationRating,MerchantWaitRating,CustomerRating,OverallRating"]
        for row in rows:
            feedback = self.db.conn.execute(
                "SELECT rating, notes, parking_rating, navigation_rating, "
                "merchant_wait_rating, customer_rating, overall_rating "
                "FROM trip_feedback WHERE trip_id = ?", (row["id"],)
            ).fetchone()
            rating = feedback["rating"] if feedback else ""
            notes = (feedback["notes"] or "").replace(",", ";").replace("\n", " ") if feedback else ""
            parking = (feedback["parking_rating"] or "") if feedback else ""
            navigation = (feedback["navigation_rating"] or "") if feedback else ""
            merchant_wait = (feedback["merchant_wait_rating"] or "") if feedback else ""
            customer = (feedback["customer_rating"] or "") if feedback else ""
            overall = (feedback["overall_rating"] or "") if feedback else ""
            date_str = datetime.fromtimestamp(row["start_time"]).strftime("%Y-%m-%d %H:%M")
            lines.append(
                f"{row['id']},{date_str},{row['mode']},{row['distance_km']:.2f},"
                f"{row['time_efficiency_score']:.0f},{row['safety_score']:.0f},"
                f"{row['geofence_hit_ratio']:.0f},{row['composite_score']:.0f},"
                f"{row['fuel_cost_estimate']:.2f},{bool(row['was_interrupted'])},"
                f"{rating},{notes},{parking},{navigation},{merchant_wait},{customer},{overall}"
            )
        return "\n".join(lines)

    @staticmethod
    def _format_table(title, headers, rows):
        """
        Shared formatter for every section of export_full_report --
        replaces the previous raw comma-separated lines (fine for CSV
        import, genuinely hard to actually read as plain text) with
        column-aligned tables: consistent width per column, a header row,
        a separator line, then data -- the same visual structure in every
        section rather than each one being formatted slightly differently.
        Empty tables get a clear "no data yet" line instead of a bare
        header with nothing under it.
        """
        lines = [f"=== {title} ==="]
        if not rows:
            lines.append("(No data recorded yet.)")
            return "\n".join(lines)

        str_rows = [[str(cell) for cell in row] for row in rows]
        widths = [len(h) for h in headers]
        for row in str_rows:
            for i, cell in enumerate(row):
                widths[i] = max(widths[i], len(cell))

        def format_row(cells):
            return "  ".join(cell.ljust(widths[i]) for i, cell in enumerate(cells))

        lines.append(format_row(headers))
        lines.append("  ".join("-" * w for w in widths))
        for row in str_rows:
            lines.append(format_row(row))
        lines.append(f"({len(rows)} row{'s' if len(rows) != 1 else ''})")
        return "\n".join(lines)

    def export_full_report(self):
        """
        A comprehensive, multi-section report covering EVERY metric this
        app records -- not just trips. Previously, only the trips table
        (and an incomplete version of it) had any export path at all;
        safety events, delays, messages, restaurant wait history, distance
        accuracy, delivery speed history, and accept/decline/timeout
        outcomes existed only as one-screen-at-a-time in-app views with
        no way to get them out of the app at all.

        Every section uses the SAME column-aligned table format (see
        _format_table) -- previously each section was raw comma-separated
        text, technically consistent as CSV but genuinely hard to read as
        plain text, especially in the PDF export where alignment actually
        renders visibly with the monospace font used there.
        """
        generated_at = datetime.now().strftime("%Y-%m-%d %I:%M %p")
        sections = [f"DASHER MONITOR -- FULL REPORT\nGenerated: {generated_at}"]

        trip_rows = self.db.conn.execute("""
            SELECT id, start_time, mode, distance_km, time_efficiency_score, safety_score,
                   geofence_hit_ratio, composite_score, fuel_cost_estimate, was_interrupted
            FROM trips WHERE end_time IS NOT NULL ORDER BY id ASC
        """).fetchall()
        rows = []
        for r in trip_rows:
            when = datetime.fromtimestamp(r["start_time"]).strftime("%Y-%m-%d %H:%M")
            feedback = self.db.conn.execute(
                "SELECT rating, overall_rating FROM trip_feedback WHERE trip_id = ?", (r["id"],)
            ).fetchone()
            rating = feedback["overall_rating"] or feedback["rating"] if feedback else ""
            rows.append([
                r["id"], when, r["mode"], f"{r['distance_km']:.2f}", f"{r['time_efficiency_score']:.0f}",
                f"{r['safety_score']:.0f}", f"{r['geofence_hit_ratio']:.0f}", f"{r['composite_score']:.0f}",
                f"{r['fuel_cost_estimate']:.2f}", rating,
                "Interrupted" if r["was_interrupted"] else "",
            ])
        sections.append(self._format_table("TRIPS",
            ["ID", "Date", "Mode", "KM", "TimeEff%", "Safety%", "Stops%", "Overall%", "Fuel$", "Rating", "Note"],
            rows))

        event_rows = self.db.conn.execute(
            "SELECT trip_id, event_type, timestamp, magnitude FROM events ORDER BY trip_id, timestamp"
        ).fetchall()
        rows = [[r["trip_id"], r["event_type"], datetime.fromtimestamp(r["timestamp"]).strftime("%Y-%m-%d %H:%M:%S"),
                 f"{r['magnitude']:.2f}"] for r in event_rows]
        sections.append(self._format_table("SAFETY EVENTS", ["TripID", "Type", "Timestamp", "Magnitude"], rows))

        delay_rows = self.db.conn.execute(
            "SELECT trip_id, duration_seconds, timestamp FROM delays ORDER BY trip_id, timestamp"
        ).fetchall()
        rows = [[r["trip_id"], r["duration_seconds"], datetime.fromtimestamp(r["timestamp"]).strftime("%Y-%m-%d %H:%M:%S")]
                for r in delay_rows]
        sections.append(self._format_table("DELAYS", ["TripID", "DurationSec", "Timestamp"], rows))

        message_rows = self.db.conn.execute("""
            SELECT trip_id, sender, body, timestamp, extracted_instruction FROM messages
            WHERE extracted_instruction IS NOT NULL ORDER BY trip_id, timestamp
        """).fetchall()
        rows = []
        for r in message_rows:
            when = datetime.fromtimestamp(r["timestamp"]).strftime("%Y-%m-%d %H:%M:%S")
            body_clean = (r["body"] or "").replace("\n", " ")
            instruction_clean = (r["extracted_instruction"] or "")
            rows.append([r["trip_id"], r["sender"], body_clean, when, instruction_clean])
        sections.append(self._format_table("MESSAGES", ["TripID", "Sender", "Body", "Timestamp", "Instruction"], rows))

        wait_rows = self.db.conn.execute(
            "SELECT restaurant_name, avg_wait_minutes, sample_count FROM restaurant_wait_history ORDER BY sample_count DESC"
        ).fetchall()
        rows = [[r["restaurant_name"], f"{r['avg_wait_minutes']:.1f}", r["sample_count"]] for r in wait_rows]
        sections.append(self._format_table("RESTAURANT WAIT HISTORY", ["Restaurant", "AvgWaitMin", "Samples"], rows))

        accuracy_rows = self.db.conn.execute("""
            SELECT trip_id, restaurant_name, claimed_distance_km, actual_deadhead_km,
                   actual_delivery_km, actual_total_km, timestamp
            FROM offer_distance_accuracy ORDER BY timestamp
        """).fetchall()
        rows = []
        for r in accuracy_rows:
            when = datetime.fromtimestamp(r["timestamp"]).strftime("%Y-%m-%d %H:%M:%S")
            rows.append([r["trip_id"], r["restaurant_name"], f"{r['claimed_distance_km']:.2f}",
                         f"{r['actual_deadhead_km']:.2f}", f"{r['actual_delivery_km']:.2f}",
                         f"{r['actual_total_km']:.2f}", when])
        sections.append(self._format_table("DISTANCE ACCURACY",
            ["TripID", "Restaurant", "ClaimedKM", "DeadheadKM", "DeliveryKM", "TotalKM", "Timestamp"], rows))

        speed_row = self.db.conn.execute(
            "SELECT avg_speed_kmh, sample_count FROM delivery_speed_history WHERE id = 1"
        ).fetchone()
        rows = [[f"{speed_row['avg_speed_kmh']:.1f}", speed_row["sample_count"]]] if speed_row else []
        sections.append(self._format_table("DELIVERY SPEED HISTORY", ["AvgSpeedKmh", "Samples"], rows))

        walk_row = self.db.conn.execute(
            "SELECT avg_speed_kmh, sample_count FROM walking_speed_history WHERE id = 1"
        ).fetchone()
        rows = [[f"{walk_row['avg_speed_kmh']:.1f}", walk_row["sample_count"]]] if walk_row else []
        sections.append(self._format_table("WALKING SPEED HISTORY", ["AvgSpeedKmh", "Samples"], rows))

        gap_row = self.db.conn.execute(
            "SELECT avg_gap_seconds, sample_count FROM park_to_walk_gap_history WHERE id = 1"
        ).fetchone()
        rows = [[f"{gap_row['avg_gap_seconds']:.0f}", gap_row["sample_count"]]] if gap_row else []
        sections.append(self._format_table("PARK-TO-WALK GAP HISTORY", ["AvgGapSeconds", "Samples"], rows))

        outcome_rows = self.db.conn.execute("""
            SELECT restaurant_name, payout, distance_km, smart_score, outcome, timestamp, components_json
            FROM offer_outcomes WHERE is_test_data = 0 ORDER BY timestamp
        """).fetchall()
        rows = []
        for r in outcome_rows:
            when = datetime.fromtimestamp(r["timestamp"]).strftime("%Y-%m-%d %H:%M:%S")
            # Full 6-factor breakdown, not just the final aggregate score
            # -- the data was always saved (see record_offer_outcome/
            # record_offer_timeout), just never surfaced in this specific
            # export before. "n/a" for any older/malformed row missing it.
            components = {}
            if r["components_json"]:
                try:
                    components = json.loads(r["components_json"])
                except (ValueError, TypeError):
                    components = {}
            factor_cols = [
                f"{components[k]:.0f}" if k in components else "n/a"
                for k in ("base_score", "hourly_score", "deadhead_score",
                          "wait_score", "time_score", "weather_score")
            ]
            rows.append([r["restaurant_name"], f"{r['payout']:.2f}", f"{r['distance_km']:.2f}",
                         f"{r['smart_score']:.1f}"] + factor_cols + [r["outcome"], when])
        sections.append(self._format_table("ACCEPT/DECLINE/TIMEOUT OUTCOMES",
            ["Restaurant", "Payout", "DistanceKM", "Score", "Base", "Hourly", "Deadhead",
             "Wait", "Time", "Weather", "Outcome", "Timestamp"], rows))

        return "\n\n".join(sections)

    def get_address_book(self):
        """
        Lists every restaurant with learned history (wait time samples,
        deadhead samples) -- the data already existed (used internally
        for the Smart Score's learned factors), but had no dedicated
        browsable view of its own until now. Restaurant-specific deadhead
        samples are joined in from offer_distance_accuracy for a fuller
        picture of what's actually been learned about each location.
        """
        wait_rows = self.db.conn.execute("""
            SELECT restaurant_name, avg_wait_minutes, sample_count
            FROM restaurant_wait_history ORDER BY sample_count DESC
        """).fetchall()

        entries = []
        for row in wait_rows:
            deadhead_row = self.db.conn.execute("""
                SELECT AVG(actual_deadhead_km) AS avg_km, COUNT(*) AS cnt
                FROM offer_distance_accuracy WHERE restaurant_name = ?
            """, (row["restaurant_name"],)).fetchone()
            parking = json.loads(self.get_parking_difficulty_rating(row["restaurant_name"]))
            entries.append({
                "restaurant_name": row["restaurant_name"],
                "avg_wait_minutes": round(row["avg_wait_minutes"], 1),
                "wait_samples": row["sample_count"],
                "avg_deadhead_km": round(deadhead_row["avg_km"], 2) if deadhead_row["cnt"] else None,
                "deadhead_samples": deadhead_row["cnt"],
                "parking_difficulty": parking.get("label") if parking.get("has_rating") else None,
                "parking_difficulty_samples": parking.get("sample_count", 0),
            })
        return json.dumps({"entries": entries})

    def _build_trip_summary_dict(self, row):
        """Shared by get_last_trip_summary() and get_trip_summary_by_id()."""
        trip_id = row["id"]
        messages = self.db.conn.execute(
            "SELECT body, timestamp, extracted_instruction FROM messages "
            "WHERE trip_id = ? AND extracted_instruction IS NOT NULL "
            "ORDER BY timestamp",
            (trip_id,),
        ).fetchall()
        stops = self.db.conn.execute(
            "SELECT address, matched, arrival_time FROM stops WHERE trip_id = ?",
            (trip_id,),
        ).fetchall()
        events = self.db.conn.execute(
            "SELECT event_type, timestamp, magnitude FROM events "
            "WHERE trip_id = ? ORDER BY timestamp",
            (trip_id,),
        ).fetchall()
        delays = self.db.conn.execute(
            "SELECT duration_seconds, timestamp FROM delays "
            "WHERE trip_id = ? ORDER BY timestamp",
            (trip_id,),
        ).fetchall()

        event_counts = {}
        for e in events:
            event_counts[e["event_type"]] = event_counts.get(e["event_type"], 0) + 1

        feedback_row = self.db.conn.execute(
            "SELECT rating, notes, parking_rating, navigation_rating, "
            "merchant_wait_rating, customer_rating, overall_rating "
            "FROM trip_feedback WHERE trip_id = ?", (trip_id,)
        ).fetchone()

        # Full offer breakdown from accept-time, shown here (post-trip) in
        # full detail -- the live badge only ever shows the single score
        # number + a short warning, by design, so it doesn't need to be
        # mentally combined while you're actually deciding whether to
        # accept. This is where the full picture becomes available.
        offer_score_snapshot = None
        if row["offer_score_snapshot_json"]:
            try:
                offer_score_snapshot = json.loads(row["offer_score_snapshot_json"])
            except (ValueError, TypeError):
                offer_score_snapshot = None

        # Phase-by-phase timing breakdown -- "where did the time go" for
        # THIS specific delivery, not just a learned average. Simplified,
        # single-primary-flow scope: only the first pickup and first
        # dropoff, same honest limitation as the underlying capture
        # points for a multi-stop batch. Any phase whose timestamps
        # weren't both captured (e.g. no walking ever detected, or an
        # older trip from before this was added) is simply omitted
        # rather than guessed at.
        phase_breakdown = {}
        if row["start_time"] and row["pickup_arrival_ts"]:
            phase_breakdown["driving_to_pickup_seconds"] = row["pickup_arrival_ts"] - row["start_time"]
        if row["pickup_arrival_ts"] and row["pickup_departure_ts"]:
            phase_breakdown["wait_at_restaurant_seconds"] = row["pickup_departure_ts"] - row["pickup_arrival_ts"]
        if row["pickup_departure_ts"] and row["dropoff_arrival_ts"]:
            phase_breakdown["driving_to_dropoff_seconds"] = row["dropoff_arrival_ts"] - row["pickup_departure_ts"]
        if row["dropoff_arrival_ts"] and row["walking_confirmed_ts"]:
            phase_breakdown["parking_to_walking_seconds"] = row["walking_confirmed_ts"] - row["dropoff_arrival_ts"]
        # The one stage that was missing entirely: door-to-marked-complete --
        # everything from actually reaching the door (walking confirmed, if
        # that was detected this trip; otherwise dropoff arrival itself) to
        # the delivery being marked done and the trip ending. Covers photo/
        # knock/hand-off time, not captured by any earlier phase.
        completing_dropoff_start_ts = row["walking_confirmed_ts"] or row["dropoff_arrival_ts"]
        if completing_dropoff_start_ts and row["end_time"]:
            phase_breakdown["completing_dropoff_seconds"] = row["end_time"] - completing_dropoff_start_ts

        deadline_comparison = None
        if row["deadline_text"] and row["end_time"]:
            deadline_ts = OfferScreenParser.compute_deadline_timestamp(row["deadline_text"], row["start_time"])
            if deadline_ts is not None:
                seconds_relative_to_deadline = row["end_time"] - deadline_ts
                deadline_comparison = {
                    "deadline_text": row["deadline_text"],
                    "was_late": seconds_relative_to_deadline > 0,
                    "seconds_relative_to_deadline": seconds_relative_to_deadline,
                }

        return {
            "found": True,
            "trip_id": trip_id,
            "mode": row["mode"],
            "start_time": row["start_time"],
            "end_time": row["end_time"],
            "distance_km": row["distance_km"],
            "time_efficiency_score": row["time_efficiency_score"],
            "safety_score": row["safety_score"],
            "geofence_hit_ratio": row["geofence_hit_ratio"],
            "composite_score": row["composite_score"],
            "fuel_cost_estimate": row["fuel_cost_estimate"],
            "was_interrupted": bool(row["was_interrupted"]),
            "offer_score_snapshot": offer_score_snapshot,
            "pickup_address": row["pickup_address"],
            "phase_breakdown": phase_breakdown,
            "deadline_comparison": deadline_comparison,
            "feedback_rating": feedback_row["rating"] if feedback_row else None,
            "feedback_notes": feedback_row["notes"] if feedback_row else None,
            "feedback_parking": feedback_row["parking_rating"] if feedback_row else None,
            "feedback_navigation": feedback_row["navigation_rating"] if feedback_row else None,
            "feedback_merchant_wait": feedback_row["merchant_wait_rating"] if feedback_row else None,
            "feedback_customer": feedback_row["customer_rating"] if feedback_row else None,
            "feedback_overall": feedback_row["overall_rating"] if feedback_row else None,
            "instructions": [
                {"body": m["body"], "extracted": m["extracted_instruction"],
                 "timestamp": m["timestamp"]}
                for m in messages
            ],
            "stops": [
                {"address": s["address"], "matched": bool(s["matched"]),
                 "arrival_time": s["arrival_time"]}
                for s in stops
            ],
            "event_counts": event_counts,
            "delay_count": len(delays),
            "total_delay_seconds": sum(d["duration_seconds"] for d in delays),
        }

    # Offer intelligence ----------------------------------------------
    def is_dash_paused_screen(self, lines_json):
        """Returns True if this looks like a "Dash Paused" screen -- see DashPauseDetector."""
        lines = json.loads(lines_json)
        return DashPauseDetector.is_paused_screen(lines)

    def is_dash_resumed_screen(self, lines_json):
        """Returns True if this looks like a "Resume Dash" screen -- see DashPauseDetector."""
        lines = json.loads(lines_json)
        return DashPauseDetector.is_resumed_screen(lines)

    def parse_offer_screen(self, lines_json, current_lat=None, current_lon=None):
        """
        lines_json: JSON array of on-screen text nodes, top-to-bottom, as read
        by DasherAccessibilityService while the offer screen is showing.
        current_lat/current_lon: your real current position, if available
        (used for zone-based traffic-risk learning -- see
        SmartScoreEngine._get_traffic_risk_by_zone). Optional -- falls
        back to hour-only/generic traffic risk if not provided.
        Returns JSON: parsed fields + smart_score (if enough data was found).

        is_batch_offer: True if this looks like a multi-stop/batch order
        (see OfferScreenParser.is_batch_offer's honesty note -- this is
        detection only, not a real per-stop parse). When True, the
        payout/distance/score fields still get computed the same way as
        any single offer, but they're likely WRONG for a batch (probably
        reflect only one leg of a multi-stop order) -- flagged so the
        caller can warn rather than present a confident-looking but
        potentially misleading score.
        """
        lines = json.loads(lines_json)
        if not OfferScreenParser.is_offer_screen(lines):
            return json.dumps({"is_offer_screen": False})

        parsed = OfferScreenParser.parse(lines)
        parsed["is_offer_screen"] = True
        parsed["is_batch_offer"] = OfferScreenParser.is_batch_offer(lines)
        parsed["countdown_seconds"] = OfferScreenParser.extract_countdown_seconds(lines)

        if parsed["payout"] is not None and parsed["distance_km"] is not None:
            # Distance-based estimate, NOT deadline-based: "time until the
            # deadline" is slack time, not travel time, and produced a
            # misleading $/hr that swung wildly depending on what moment you
            # happened to open the offer (e.g. $5/hr if opened hours early).
            est_minutes = self.smart_score.estimate_minutes_from_distance(
                parsed["distance_km"]
            ) or 20.0
            hour_24 = datetime.now().hour
            parsed["smart_score"] = self.smart_score.calculate(
                parsed["payout"], parsed["distance_km"], est_minutes,
                parsed["restaurant_name"] or "unknown",
                hour_24, current_lat, current_lon,
            )
        return json.dumps(parsed)

    def parse_offer_notification(self, title, text):
        """
        Best-effort offer detection from a Dasher NOTIFICATION's title/text
        -- a completely different, much terser text source than the full
        offer screen parse_offer_screen() handles. This exists because
        offers can arrive as a system notification while you're doing
        something else entirely (e.g. navigating via Google Maps), not
        just while the Dasher app itself is on screen -- previously,
        monitoring only ever detected offers by reading the Dasher app's
        own screen content, which meant nothing happened at all if you
        never actually opened the app to look at the offer directly.

        CONFIRMED against a real notification: title="New Delivery!",
        text="New Order: Go to <restaurant>". This format has NO payout
        or distance in it at all -- it's purely an announcement telling
        you to go check the app for details. The original version of
        this parser required a dollar amount to recognize anything as an
        offer at all, so this real, common notification type was
        silently failing every single time. Now recognized as a real
        offer with no score (nothing to compute a score from), rather
        than being ignored outright.

        HONESTY NOTE: the dollar-amount extraction path below still isn't
        confirmed against a real sample that actually includes a payout
        in the notification text itself -- only this no-payout
        "New Delivery!" format has been confirmed real so far.
        """
        combined = f"{title or ''} {text or ''}"

        # Confirmed real format: no payout at all, just a heads-up to
        # check the app. Checked first, since a real sample of this exact
        # text confirmed the app actually sends notifications this way.
        go_to_match = re.search(r"go to\s+(.+?)$", text or "", re.IGNORECASE)
        if go_to_match and "new" in (title or "").lower():
            restaurant_name = go_to_match.group(1).strip()
            return json.dumps({
                "is_offer": True,
                "payout": None,
                "distance_km": None,
                "restaurant_name": restaurant_name or "Unknown (from notification)",
            })

        payout_match = re.search(r"\$(\d+\.\d{2})", combined)
        if not payout_match:
            return json.dumps({"is_offer": False})
        payout = float(payout_match.group(1))

        distance_km = None
        km_match = re.search(r"(\d+(?:\.\d+)?)\s*km\b", combined, re.IGNORECASE)
        mi_match = re.search(r"(\d+(?:\.\d+)?)\s*mi(?:les?)?\b", combined, re.IGNORECASE)
        if km_match:
            distance_km = float(km_match.group(1))
        elif mi_match:
            distance_km = float(mi_match.group(1)) * 1.60934

        restaurant_name = (title or "").strip() or "Unknown (from notification)"
        result = {
            "is_offer": True,
            "payout": payout,
            "distance_km": round(distance_km, 2) if distance_km is not None else None,
            "restaurant_name": restaurant_name,
        }

        if distance_km is not None:
            est_minutes = self.smart_score.estimate_minutes_from_distance(distance_km) or 20.0
            hour_24 = datetime.now().hour
            result["smart_score"] = self.smart_score.calculate(
                payout, distance_km, est_minutes, restaurant_name, hour_24,
            )
        # If no distance was found in the notification text, we still
        # report the payout (so it can at least be announced/shown) but
        # can't compute a real score without a distance -- $/km and $/hr
        # both need it.
        return json.dumps(result)

    def is_dropoff_screen(self, lines_json):
        """
        Returns True if this looks like the real post-accept "Deliver to
        X" screen -- see DropoffScreenParser. Built from two real
        screenshots, closing the single most-flagged gap in this whole
        project: real dropoff address extraction (previously every
        dropoff used placeholder 0.0/0.0 coordinates).
        """
        lines = json.loads(lines_json)
        return DropoffScreenParser.is_dropoff_screen(lines)

    def parse_dropoff_screen(self, lines_json):
        """
        Parses the real post-accept "Deliver to X" screen. Returns the
        parsed fields including full_address, assembled specifically for
        geocoding (see GoogleApiHelper on the Java side).
        """
        lines = json.loads(lines_json)
        return json.dumps(DropoffScreenParser.parse(lines))

    def add_pickup(self, restaurant_name, lat, lon, claimed_distance_km=None, score_snapshot_json=None,
                   deadline_text=None, address=None):
        """
        Registers the pickup location so real wait time can be measured
        once the driver arrives and later leaves. Called once per offer,
        typically right after parse_offer_screen identifies the restaurant
        (lat/lon start as placeholders until real geocoding resolves --
        see update_pickup_coordinates).

        CRITICAL BUG FIX: this wrapper was completely missing from
        DriveMonitorEngine (only TripManager had add_pickup) -- meaning
        every real call from Java (engine.callAttr("add_pickup", ...))
        threw an AttributeError, silently caught by the defensive
        try/catch around every call site. This means pickup tracking --
        and everything built on top of it (deadhead learning, wait-time
        learning) -- has likely never actually worked in the field,
        despite testing correctly against TripManager directly. Found by
        directly testing engine.add_pickup() rather than assuming.

        score_snapshot_json: the full Smart Score breakdown (as JSON) for
        the offer that led to this pickup, if available -- stored so it
        can be shown in full after the trip completes (see
        _build_trip_summary_dict), rather than only the simplified
        single-number view shown live at accept-time.
        deadline_text: the offer's real "Deliver by X pm" text, carried
        through to the trip row for the post-trip phase-timing breakdown.
        address: usually None here -- see TripManager.add_pickup's doc.
        """
        self.trip_manager.add_pickup(restaurant_name, lat, lon, claimed_distance_km, score_snapshot_json,
                                      deadline_text, address)

    def update_pickup_coordinates(self, lat, lon):
        """
        Called once GoogleApiHelper's async geocoding resolves, replacing
        the placeholder coordinates with real ones for the current pickup.
        """
        self.trip_manager.update_pickup_coordinates(lat, lon)

    def update_pickup_address(self, address):
        """
        Called once GoogleApiHelper's async geocodeAddressWithFormatted
        resolves for the current pickup's restaurant name -- see
        TripManager.update_pickup_address's doc.
        """
        self.trip_manager.update_pickup_address(address)

    def get_current_pickup_restaurant(self):
        """
        Restaurant name of the pickup currently registered (offer
        accepted, not yet departed), or "" if there isn't one -- lets the
        UI show/enable a "pickup notes" affordance only while it's
        actually relevant, without exposing the whole internal pickup
        dict just for this one field.
        """
        if self.trip_manager.pickup and not self.trip_manager.pickup.get("recorded"):
            return self.trip_manager.pickup.get("restaurant_name") or ""
        return ""

    def get_pickup_notes(self, restaurant_name):
        """
        Whatever note (if any) was previously saved for this restaurant's
        pickup location -- e.g. "gate code 1234", "enter through side
        door". Returns "" (never None/null) when nothing's been saved yet,
        so the Java side can always just display the result directly
        without an extra null check.
        """
        row = self.db.conn.execute(
            "SELECT notes FROM pickup_location_notes WHERE restaurant_name = ?",
            (restaurant_name,)
        ).fetchone()
        return row["notes"] if row and row["notes"] else ""

    def save_pickup_notes(self, restaurant_name, notes):
        """
        Persists a note for this restaurant's pickup location, keyed by
        name so it's still there next time an offer comes in from the
        same place -- same "learn per restaurant" pattern already used
        for parking_difficulty_feedback and restaurant_wait_history. An
        empty/blank note clears any existing one rather than storing a
        blank row.
        """
        trimmed = (notes or "").strip()
        if trimmed:
            self.db.conn.execute(
                "INSERT INTO pickup_location_notes (restaurant_name, notes, updated_ts) VALUES (?, ?, ?) "
                "ON CONFLICT(restaurant_name) DO UPDATE SET notes = excluded.notes, updated_ts = excluded.updated_ts",
                (restaurant_name, trimmed, time.time())
            )
        else:
            self.db.conn.execute(
                "DELETE FROM pickup_location_notes WHERE restaurant_name = ?", (restaurant_name,)
            )
        self.db.conn.commit()

    def record_live_traffic_delay(self, delay_ratio):
        """
        Called once GoogleApiHelper's async Distance Matrix query resolves
        for the current-location -> pickup route. Overrides the personal-
        history-based traffic proxy with real live traffic data for the
        next score calculation, as long as it's still fresh (see
        SmartScoreEngine._get_traffic_risk).
        """
        self.smart_score.record_live_traffic_delay(delay_ratio)

    def record_live_weather(self, precipitation_mm, wind_speed_kmh, temperature_c):
        """
        Called once WeatherHelper's async Open-Meteo query resolves.

        SAME BUG CLASS AS add_pickup: this wrapper was completely missing
        from DriveMonitorEngine (only SmartScoreEngine had it) -- meaning
        every real call from Java (engine.callAttr("record_live_weather",
        ...)) threw an AttributeError, silently caught by the defensive
        try/catch around the call site. The live-weather feature has
        likely never actually worked in the field. Found by systematically
        checking every engine.callAttr(...) call against DriveMonitorEngine's
        actual method list, immediately after finding the identical bug in
        add_pickup -- worth checking for every time a similar wrapper-style
        bug is found once, since it clearly isn't a one-off mistake pattern.
        """
        self.smart_score.record_live_weather(precipitation_mm, wind_speed_kmh, temperature_c)

    def recalculate_personal_calibration(self):
        """Wrapper -- see SmartScoreEngine.recalculate_personal_calibration for the actual logic."""
        return self.smart_score.recalculate_personal_calibration()

    def get_personal_calibration_summary(self):
        """Wrapper -- see SmartScoreEngine.get_personal_calibration_summary for the actual logic."""
        return self.smart_score.get_personal_calibration_summary()

    def reset_personal_calibration(self):
        """Wrapper -- see SmartScoreEngine.reset_personal_calibration for the actual logic."""
        self.smart_score.reset_personal_calibration()

    def get_distance_accuracy_summary(self):
        """
        Empirically answers "does the offer screen's distance figure include
        the drive to the restaurant, or just the delivery leg?" using this
        driver's own recorded trips -- rather than guessing or relying on
        unofficial/unconfirmed third-party sources. Compares the average
        error of two hypotheses (claimed == delivery-only vs claimed ==
        total trip) and reports which one fits better, plus how many trips
        that conclusion is based on.
        """
        rows = self.db.conn.execute("""
            SELECT claimed_distance_km, actual_deadhead_km, actual_delivery_km, actual_total_km
            FROM offer_distance_accuracy
        """).fetchall()

        if not rows:
            return json.dumps({"sample_count": 0})

        delivery_only_errors = []
        total_trip_errors = []
        for row in rows:
            claimed = row["claimed_distance_km"]
            if claimed is None:
                continue
            delivery_only_errors.append(abs(claimed - row["actual_delivery_km"]))
            total_trip_errors.append(abs(claimed - row["actual_total_km"]))

        if not delivery_only_errors:
            return json.dumps({"sample_count": 0})

        avg_delivery_only_error = sum(delivery_only_errors) / len(delivery_only_errors)
        avg_total_trip_error = sum(total_trip_errors) / len(total_trip_errors)
        conclusion = ("delivery_only" if avg_delivery_only_error < avg_total_trip_error
                      else "total_trip")

        return json.dumps({
            "sample_count": len(delivery_only_errors),
            "avg_delivery_only_error_km": round(avg_delivery_only_error, 3),
            "avg_total_trip_error_km": round(avg_total_trip_error, 3),
            "conclusion": conclusion,
        })

    def reset_all_data(self):
        """
        Wipes every table -- trips, stops, events, delays, messages,
        restaurant wait history, offer distance accuracy, delivery speed
        history, and trusted contacts. Previously there was no way to
        start fresh short of manually clearing app storage from Android
        Settings. Does NOT restart the app process -- the in-memory
        engine singleton and any active trip state persist until the
        service/activity is actually restarted; this only clears
        persisted database rows. Deliberately does NOT clear
        diagnostic_log -- see clear_diagnostic_log() for that separately,
        since debugging history and trip data are different concerns.
        """
        tables = [
            "trips", "stops", "events", "delays", "messages",
            "restaurant_wait_history", "offer_distance_accuracy",
            "delivery_speed_history", "trusted_senders",
        ]
        for table in tables:
            self.db.conn.execute(f"DELETE FROM {table}")
        self.db.conn.commit()
        self.stops_buffer = StopsBuffer()

    DIAGNOSTIC_LOG_ROTATION_LIMIT = 500

    def log_diagnostic(self, category, message):
        """
        Persistent, in-app diagnostic log -- NOT a log of every function
        call (that would hurt battery/performance for little benefit),
        but of the events that actually matter for debugging: caught
        exceptions, service lifecycle transitions, offer detections, API
        call outcomes, and a periodic heartbeat. Previously these were
        either silently swallowed (many defensive try/catch blocks had
        only a code comment, no actual record) or only visible via
        Android's own system logs (logcat), which are ephemeral and need
        a computer connected via ADB to view -- not something checkable
        right after something goes wrong out in the field.

        Rotates to a plain-text archive file (instead of deleting the
        oldest entries) once the live table reaches
        DIAGNOSTIC_LOG_ROTATION_LIMIT -- nothing is lost, the live table
        just resets to empty so it stays fast to query, and the full
        history is still readable afterward from "View Diagnostic Log
        Archives".
        """
        self.db.conn.execute(
            "INSERT INTO diagnostic_log (timestamp, category, message) VALUES (?, ?, ?)",
            (time.time(), category, message),
        )
        self.db.conn.commit()

        count = self.db.conn.execute("SELECT COUNT(*) AS cnt FROM diagnostic_log").fetchone()["cnt"]
        if count >= self.DIAGNOSTIC_LOG_ROTATION_LIMIT:
            self._rotate_diagnostic_log_to_file()

    def _rotate_diagnostic_log_to_file(self):
        rows = self.db.conn.execute(
            "SELECT timestamp, category, message FROM diagnostic_log ORDER BY id ASC"
        ).fetchall()
        if not rows:
            return

        archive_dir = os.path.join(self.files_dir, "diagnostic_archives")
        os.makedirs(archive_dir, exist_ok=True)

        first_ts = datetime.fromtimestamp(rows[0]["timestamp"]).strftime("%Y-%m-%d_%H-%M-%S")
        last_ts = datetime.fromtimestamp(rows[-1]["timestamp"]).strftime("%Y-%m-%d_%H-%M-%S")
        filename = f"diagnostic_log_{first_ts}_to_{last_ts}.txt"
        filepath = os.path.join(archive_dir, filename)

        with open(filepath, "w") as f:
            for row in rows:
                when = datetime.fromtimestamp(row["timestamp"]).strftime("%Y-%m-%d %H:%M:%S")
                f.write(f"[{when}] {row['category']}: {row['message']}\n")

        self.db.conn.execute("DELETE FROM diagnostic_log")
        self.db.conn.commit()

    def get_diagnostic_log(self, limit=200):
        rows = self.db.conn.execute(
            "SELECT timestamp, category, message FROM diagnostic_log ORDER BY id DESC LIMIT ?",
            (limit,),
        ).fetchall()
        return json.dumps({
            "entries": [
                {"timestamp": r["timestamp"], "category": r["category"], "message": r["message"]}
                for r in rows
            ]
        })

    def export_full_diagnostic_history(self):
        """
        Genuine full history -- previously both Copy Log and Share Log
        were hard-capped at the 200 most recent entries, which given how
        often HEARTBEAT fires could represent as little as 10-20 minutes
        of a shift. This combines every archived file (chronologically,
        oldest first) with the full current live log (not capped at all),
        into one complete text export covering everything ever recorded.
        """
        parts = []
        archive_dir = os.path.join(self.files_dir, "diagnostic_archives")
        if os.path.isdir(archive_dir):
            for filename in sorted(os.listdir(archive_dir)):
                filepath = os.path.join(archive_dir, filename)
                if os.path.isfile(filepath):
                    with open(filepath, "r") as f:
                        parts.append(f.read())

        live_rows = self.db.conn.execute(
            "SELECT timestamp, category, message FROM diagnostic_log ORDER BY id ASC"
        ).fetchall()
        live_lines = []
        for row in live_rows:
            when = datetime.fromtimestamp(row["timestamp"]).strftime("%Y-%m-%d %H:%M:%S")
            live_lines.append(f"[{when}] {row['category']}: {row['message']}")
        if live_lines:
            parts.append("\n".join(live_lines))

        return json.dumps({"content": "\n".join(parts), "found": len(parts) > 0})

    def clear_diagnostic_log(self):
        self.db.conn.execute("DELETE FROM diagnostic_log")
        self.db.conn.commit()

    def list_diagnostic_archives(self):
        """Archive filenames, newest first, for "View Diagnostic Log Archives"."""
        archive_dir = os.path.join(self.files_dir, "diagnostic_archives")
        if not os.path.isdir(archive_dir):
            return json.dumps({"files": []})
        files = sorted(os.listdir(archive_dir), reverse=True)
        return json.dumps({"files": files})

    def read_diagnostic_archive(self, filename):
        """
        Reads one archive file back. filename must be exactly one of the
        names returned by list_diagnostic_archives() -- rejects anything
        else (e.g. a path with "/" in it) so this can't be used to read
        arbitrary files elsewhere on the device.
        """
        if "/" in filename or "\\" in filename or ".." in filename:
            return json.dumps({"found": False, "error": "invalid filename"})
        archive_dir = os.path.join(self.files_dir, "diagnostic_archives")
        filepath = os.path.join(archive_dir, filename)
        if not os.path.isfile(filepath):
            return json.dumps({"found": False})
        with open(filepath, "r") as f:
            content = f.read()
        return json.dumps({"found": True, "content": content})

    # One-Tap Instant Pinpoint -----------------------------------------
    def add_stop_to_buffer(self, address, lat, lon):
        self.stops_buffer.add(address, lat, lon)
        self.trip_manager.add_stop(address, lat, lon)

    def get_stops_buffer_json(self):
        return self.stops_buffer.as_json()

    # Message intelligence (work: Dasher app / customer SMS instructions) ----
    def on_notification(self, package_name, title, text, timestamp_ms, is_messaging_style, lat=None, lon=None):
        return self.trip_manager.on_message(
            package_name, title, text, timestamp_ms, is_messaging_style, lat, lon
        )

    def is_instruction_urgent(self, instruction):
        """
        Message triage: True for delivery notes and address corrections
        (read immediately -- they affect where you're going or what to do
        right now), False for ETA/lateness updates (lower priority, can be
        batched with other low-priority messages instead of interrupting
        individually). Previously every extracted instruction was read
        aloud with equal weight and urgency, regardless of how
        time-sensitive it actually was.
        """
        return MessageIntelligence.is_urgent(instruction)

    # Trusted contacts (personal: SMS / Messenger allowlist) -----------------
    def add_trusted_sender(self, name):
        self.trusted_contacts.add(name)

    def remove_trusted_sender(self, name):
        self.trusted_contacts.remove(name)

    def get_trusted_senders_json(self):
        return json.dumps(self.trusted_contacts.list_all())

    def is_trusted_sender(self, sender_name):
        """
        Called by AppNotificationListenerService for every SMS/Messenger
        notification. Returns True if the sender's display name (as shown
        in the notification) matches an entry the user explicitly added,
        OR if no contacts have been added at all yet (reads everything by
        default in that case, rather than silently reading nothing with
        no indication why -- see TrustedContacts.is_trusted). Once at
        least one contact exists, this becomes a real allowlist again. If
        True, the app reads the message aloud; if False, it's silently
        ignored (no logging of content, no parsing) to keep this feature
        privacy-first for everyone else.
        """
        return self.trusted_contacts.is_trusted(sender_name)


# Single shared engine instance per process (Chaquopy caches module imports)
_engine_instance = None


def get_engine(files_dir):
    global _engine_instance
    if _engine_instance is None:
        _engine_instance = DriveMonitorEngine(files_dir)
    return _engine_instance
