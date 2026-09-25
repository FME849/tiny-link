package com.fme.tinylink.models;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity 
@Data
@NoArgsConstructor
public class UrlMapping implements Persistable<String> {

    @Id
    @Column(name="SHORT_CODE",length=16,nullable=false,columnDefinition="VARCHAR(255) COLLATE utf8mb4_bin")
    private String shortCode;

    @Column(name="LONG_URL")
    private String longURL;

    @Column(name="CLICK_COUNT")
    private Integer count;

    @jakarta.persistence.Transient 
    private boolean isNew = true;

    public UrlMapping(String shortCode, String longURL) {
        this.shortCode = shortCode;
        this.longURL = longURL;
        this.count = 0;
        this.isNew = false;
    }

    @Override
    public String getId() {
        return this.shortCode;
    }

    @PostPersist
    @PostLoad
    public void markNotNew() {
        this.isNew = false;
    }

}
