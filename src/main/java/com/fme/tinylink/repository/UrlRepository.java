package com.fme.tinylink.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.fme.tinylink.models.UrlData;

public interface UrlRepository extends JpaRepository<UrlData, Long> {
    public Optional<UrlData> findByShortCode(String shortCode);

    @Modifying
    @Query("UPDATE UrlData u SET u.count = u.count + 1 WHERE u.shortCode = :shortCode")
    public int increaseCountByShortCode(@Param ("shortCode") String shortCode);
}
