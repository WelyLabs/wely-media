package com.calendar.media.infrastructure.storage.listing;

import java.util.List;

/** One page of an S3 {@code ListObjectsV2} answer; {@code nextToken} is null on the last page. */
public record ListBucketPage(List<String> keys, String nextToken) {

    public ListBucketPage {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }
}
