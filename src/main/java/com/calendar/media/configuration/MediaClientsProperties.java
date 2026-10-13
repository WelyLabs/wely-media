package com.calendar.media.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * The services allowed to call this one, under {@code app.clients.<name>}: each with its own
 * secret, key prefix and bucket. A blank secret disables the client.
 */
@ConfigurationProperties("app")
public record MediaClientsProperties(Map<String, Client> clients) {

    public MediaClientsProperties {
        clients = clients == null ? Map.of() : Map.copyOf(clients);
    }

    public record Client(String secret, String prefix, String bucket) {
    }
}
