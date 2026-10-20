package com.calendar.media.domain.ports;

/**
 * Business facts worth counting, reported by the domain at the moment they are established.
 *
 * <p>A port, so the domain says what happened without knowing how it is measured.
 */
public interface MediaActivity {

    /**
     * A picture was stored, after the write succeeded.
     *
     * @param client the calling Wely service, one of a small fixed set
     * @param bytes  the size of the stored object, after normalization
     */
    void mediaStored(String client, long bytes);
}
