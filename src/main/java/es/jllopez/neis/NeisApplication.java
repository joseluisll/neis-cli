package es.jllopez.neis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NEIS - News and Events Information System.
 *
 * <p>CLI-oriented application built with Spring Boot and Spring Shell,
 * organized as a hexagonal architecture: {@code domain} defines the ports,
 * {@code application} holds the use cases, and {@code infrastructure}
 * provides the connectors (adapters) that implement the ports.</p>
 */
@SpringBootApplication
public class NeisApplication {

    public static void main(String[] args) {
        SpringApplication.run(NeisApplication.class, args);
    }
}
