-- TinyLink Performance Test Database Initialization
CREATE DATABASE IF NOT EXISTS tinylink_perf;
USE tinylink_perf;

-- Create table matching UrlMapping JPA entity
CREATE TABLE IF NOT EXISTS url_mapping (
    short_code VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    long_url VARCHAR(255) DEFAULT NULL,
    click_count INT DEFAULT 0,
    PRIMARY KEY (short_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
