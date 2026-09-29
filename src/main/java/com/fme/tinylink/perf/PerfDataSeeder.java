package com.fme.tinylink.perf;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fme.tinylink.base62.Base62Encoder;

/**
 * High-speed historical data seeder for performance testing.
 * Only activated when the 'perf-seed' profile is active.
 *
 * Populates 80,000 authentic Snowflake-ordered records into the database
 * prior to running write/read load tests.
 */
@Component
@Profile("perf-seed")
public class PerfDataSeeder implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    // Bit shift amounts matching SnowflakeIdGenerator
    private static final int WORKER_ID_SHIFT = 12;
    private static final int DATACENTER_ID_SHIFT = 17;
    private static final int TIMESTAMP_SHIFT = 22;
    private static final long CUSTOM_EPOCH = 1767225600000L; // 2026-01-01T00:00:00Z

    public PerfDataSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        int totalRows = 80_000;
        int batchSize = 2_000;

        // Base timestamp starting 1 hour in the past to precede live JMeter writes
        long baseTimestamp = System.currentTimeMillis() - 3600_000L;

        String sql = "INSERT INTO url_mapping (short_code, long_url, click_count) VALUES (?, ?, ?)";
        List<Object[]> batch = new ArrayList<>(batchSize);

        System.out.printf("Starting high-speed seeding of %d historical Snowflake Base62 records...%n", totalRows);
        long start = System.currentTimeMillis();

        for (int i = 0; i < totalRows; i++) {
            // Emulate historical Snowflake ID generation with 30ms spacing
            long simulatedTime = baseTimestamp + (i * 30L);
            long snowflakeId = ((simulatedTime - CUSTOM_EPOCH) << TIMESTAMP_SHIFT)
                    | (1L << DATACENTER_ID_SHIFT)
                    | (1L << WORKER_ID_SHIFT)
                    | (i & 4095);

            String shortCode = Base62Encoder.encode(snowflakeId);
            String longUrl = "https://example.com/archive/" + i;
            int clickCount = (int) (Math.random() * 50);

            batch.add(new Object[]{shortCode, longUrl, clickCount});

            if (batch.size() == batchSize) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        }

        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }

        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("Successfully seeded %d historical records in %d ms (%.1f rows/sec).%n",
                totalRows, elapsed, (totalRows * 1000.0) / Math.max(elapsed, 1));
    }
}
