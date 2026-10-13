package com.calendar.media.configuration;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaClientsPropertiesTest {

    @Test
    void constructor_shouldAnswerNoClient_whenNoneIsConfigured() {
        assertThat(new MediaClientsProperties(null).clients()).isEmpty();
    }

    @Test
    void constructor_shouldKeepAnImmutableCopy() {
        Map<String, MediaClientsProperties.Client> clients = new HashMap<>();
        clients.put("chat", new MediaClientsProperties.Client("s", "chat/", "b"));
        MediaClientsProperties properties = new MediaClientsProperties(clients);
        clients.clear();

        assertThat(properties.clients()).containsKey("chat");
        assertThatThrownBy(() -> properties.clients().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
