package com.fme.tinylink.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fme.tinylink.models.UrlData;

public interface UrlRepository extends JpaRepository<UrlData, Long> {}
