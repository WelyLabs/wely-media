package com.calendar.media.infrastructure.storage.listing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ListBucketPageTest {

    @Test
    @DisplayName("no keys read as none")
    void constructor_shouldReadNullKeysAsNone() {
        assertThat(new ListBucketPage(null, null).keys()).isEmpty();
    }

    @Test
    @DisplayName("the keys are copied: changing the source list later changes nothing")
    void constructor_shouldCopyTheKeys() {
        List<String> keys = new ArrayList<>(List.of("avatars/u1/a.jpg"));
        ListBucketPage page = new ListBucketPage(keys, "t");

        keys.add("avatars/u1/b.jpg");

        assertThat(page.keys()).containsExactly("avatars/u1/a.jpg");
    }
}
