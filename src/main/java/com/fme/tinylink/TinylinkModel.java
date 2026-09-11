package com.fme.tinylink;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity 
public class TinylinkModel {

    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    private Long id;

    @Column(name="SHORT_CODE",unique=true)
    private String shortCode;

    @Column(name="LONG_URL")
    private String longURL;

    @Column(name="CLICK_COUNT")
    private Integer count;

    public TinylinkModel(String shortCode, String longURL) {
        this.shortCode = shortCode;
        this.longURL = longURL;
        this.count = 0;
    }
}
