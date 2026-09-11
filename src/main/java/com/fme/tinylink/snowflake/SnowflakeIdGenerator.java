package com.fme.tinylink.snowflake;

import org.springframework.stereotype.Component;

@Component
public class SnowflakeIdGenerator {
        // Bit lengths for components
    private int DATACENTER_ID_BITS = 5;
    private int WORKER_ID_BITS = 5;
    private int SEQUENCE_BITS = 12;

    // Maximum values based on bit lengths
    private long MAX_DATACENTER_ID = (1L << DATACENTER_ID_BITS) - 1;
    private long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;

    // Bit shift amounts
    private int WORKER_ID_SHIFT = SEQUENCE_BITS;
    private int DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private int TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    // Custom Epoch (e.g., January 1, 2026, 00:00:00 UTC)
    private long CUSTOM_EPOCH = 1767225600000L;
    private long datacenterId;
    private long workerId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    /**
     * Initializes the generator with specific datacenter and worker coordinates.
     */
    public void setDataCenterId(long datacenterId) {
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException(String.format("Datacenter ID must be between 0 and %d", MAX_DATACENTER_ID));
        }
        this.datacenterId = datacenterId;
    }
    
    public void setWorkerId(long workerId) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException(String.format("Worker ID must be between 0 and %d", MAX_WORKER_ID));
        }
        this.workerId = workerId;
    }

    /**
     * Generates a unique, time-ordered ID.
     */
    public synchronized long nextId() {
        long currentTimestamp = getSystemTimestamp();

        // 1. Clock fallback protection
        if (currentTimestamp < lastTimestamp) {
            throw new RuntimeException(String.format("Clock moved backwards. Refusing to generate id for %d milliseconds", lastTimestamp - currentTimestamp));
        }

        // 2. Handle sequence increment within the same millisecond
        if (currentTimestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            // Sequence overflow in this millisecond -> block until next millisecond
            if (sequence == 0) {
                currentTimestamp = blockUntilNextMillis(lastTimestamp);
            }
        } else {
            // New millisecond reached, reset sequence
            sequence = 0L;
        }

        lastTimestamp = currentTimestamp;

        // 3. Shift components into their respective positions and combine with bitwise OR
        return ((currentTimestamp - CUSTOM_EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long blockUntilNextMillis(long lastTimestamp) {
        long timestamp = getSystemTimestamp();
        while (timestamp <= lastTimestamp) {
            timestamp = getSystemTimestamp();
        }
        return timestamp;
    }

    private long getSystemTimestamp() {
        return System.currentTimeMillis();
    }
}
