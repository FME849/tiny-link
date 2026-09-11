package com.fme.tinylink.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity 
@Data 
@NoArgsConstructor
@AllArgsConstructor
public class UrlData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="SHORT_CODE", unique=true)
    private String shortCode;

    @Column(name="LONG_URL")
    private String longURL;

    @Column(name="CLICK_COUNT")
    private Integer count;

    public UrlData(String shortCode, String longURL) {
        this.shortCode = shortCode;
        this.longURL = longURL;
        this.count = 0;
    }
}
