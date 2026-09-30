package com.fme.tinylink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan 
public class TinyLinkApplication {

	public static void main(String[] args) {
		SpringApplication.run(TinyLinkApplication.class, args);
	}

}
