package com.fme.tinylink.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fme.tinylink.models.UrlMapping;

public interface UrlRepository extends JpaRepository<UrlMapping, String> {
    public Optional<UrlMapping> findByShortCode(String shortCode);

    @Modifying
    @Query("UPDATE UrlMapping u SET u.count = u.count + :delta WHERE u.shortCode = :shortCode")
    public int increaseCountByDelta(@Param("shortCode") String shortCode, @Param("delta") int delta);
}
