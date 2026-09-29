#!/usr/bin/env python3
"""
seed-data.py - High-speed Snowflake Base62 Data Seeder for TinyLink Performance Testing.

Generates 80,000 authentic Snowflake-ordered records and pipes them directly
into MySQL (tinylink_mysql_perf container) using bulk multi-row INSERTs.
"""

import sys
import time
import subprocess

CUSTOM_EPOCH = 1767225600000  # 2026-01-01T00:00:00Z (matches SnowflakeIdGenerator)
TIMESTAMP_SHIFT = 22
DATACENTER_SHIFT = 17
WORKER_SHIFT = 12
ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

def encode_base62(num: int) -> str:
    if num == 0:
        return ALPHABET[0]
    chars = []
    while num > 0:
        remainder = num % 62
        chars.append(ALPHABET[remainder])
        num //= 62
    return "".join(reversed(chars))

def generate_sql(total_rows: int = 80000, batch_size: int = 2000) -> str:
    base_timestamp = int(time.time() * 1000) - 3600_000  # 1 hour ago
    sql_chunks = [
        "USE tinylink_perf;\n",
        "SET autocommit=0;\n",
        "SET unique_checks=0;\n",
        "SET foreign_key_checks=0;\n"
    ]

    for batch_start in range(0, total_rows, batch_size):
        values = []
        current_batch_size = min(batch_size, total_rows - batch_start)
        for i in range(batch_start, batch_start + current_batch_size):
            simulated_time = base_timestamp + (i * 30)  # 30ms spacing
            snowflake_id = ((simulated_time - CUSTOM_EPOCH) << TIMESTAMP_SHIFT) \
                           | (1 << DATACENTER_SHIFT) \
                           | (1 << WORKER_SHIFT) \
                           | (i & 4095)
            short_code = encode_base62(snowflake_id)
            long_url = f"https://example.com/archive/{i}"
            click_count = (i * 7) % 50
            values.append(f"('{short_code}','{long_url}',{click_count})")

        sql_chunks.append("INSERT INTO url_mapping (short_code, long_url, click_count) VALUES \n" + ",\n".join(values) + ";\n")

    sql_chunks.append("COMMIT;\n")
    sql_chunks.append("SET unique_checks=1;\n")
    sql_chunks.append("SET foreign_key_checks=1;\n")
    sql_chunks.append("ANALYZE TABLE url_mapping;\n")
    return "".join(sql_chunks)

def main():
    total_rows = int(sys.argv[1]) if len(sys.argv) > 1 else 80000
    print(f"Generating SQL statements for {total_rows} authentic Snowflake Base62 records...")
    t0 = time.time()
    sql_data = generate_sql(total_rows=total_rows)
    gen_time = time.time() - t0
    print(f"Generated SQL in {gen_time:.2f}s ({len(sql_data) / 1024 / 1024:.2f} MB).")

    print("Executing SQL inside 'tinylink_mysql_perf' container...")
    t1 = time.time()
    cmd = [
        "docker", "exec", "-i", "tinylink_mysql_perf",
        "mysql", "-uadmin", "-psecret", "tinylink_perf"
    ]
    process = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    stdout, stderr = process.communicate(input=sql_data)

    if process.returncode != 0:
        print(f"Error seeding data: {stderr}", file=sys.stderr)
        sys.exit(process.returncode)

    exec_time = time.time() - t1
    print(f"Successfully inserted {total_rows} records into tinylink_perf in {exec_time:.2f}s!")
    print(f"Total time: {gen_time + exec_time:.2f}s.")

if __name__ == "__main__":
    main()
