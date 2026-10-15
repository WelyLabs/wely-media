package com.calendar.media.infrastructure.storage.listing;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/** Reads a {@code ListBucketResult} with StAX, refusing a DTD, another root element or a missing token. */
public final class ListBucketResultParser {

    private static final String ROOT = "ListBucketResult";
    private static final XMLInputFactory FACTORY = secureFactory();

    private ListBucketResultParser() {
    }

    /** Throws IllegalStateException for anything but a well-formed listing. */
    public static ListBucketPage parse(byte[] xml) {
        try {
            XMLStreamReader reader = FACTORY.createXMLStreamReader(new ByteArrayInputStream(xml));
            try {
                return read(reader);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException unreadable) {
            throw new IllegalStateException("Unreadable bucket listing", unreadable);
        }
    }

    private static ListBucketPage read(XMLStreamReader reader) throws XMLStreamException {
        List<String> keys = new ArrayList<>();
        boolean truncated = false;
        String nextToken = null;
        boolean inContents = false;
        boolean rootSeen = false;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.DTD) {
                throw new IllegalStateException("A bucket listing never declares a document type");
            }
            if (event == XMLStreamConstants.END_ELEMENT && "Contents".equals(reader.getLocalName())) {
                inContents = false;
            }
            if (event != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            if (!rootSeen) {
                if (!ROOT.equals(reader.getLocalName())) {
                    throw new IllegalStateException("Not a bucket listing: " + reader.getLocalName());
                }
                rootSeen = true;
            }
            switch (reader.getLocalName()) {
                case "Contents" -> inContents = true;
                case "Key" -> {
                    if (inContents) {
                        keys.add(reader.getElementText());
                    }
                }
                case "IsTruncated" -> truncated = Boolean.parseBoolean(reader.getElementText().strip());
                case "NextContinuationToken" -> nextToken = reader.getElementText();
                default -> { }
            }
        }
        if (!rootSeen) {
            throw new IllegalStateException("Empty bucket listing");
        }
        if (truncated && (nextToken == null || nextToken.isBlank())) {
            throw new IllegalStateException("Truncated bucket listing without a continuation token");
        }
        return new ListBucketPage(keys, truncated ? nextToken : null);
    }

    private static XMLInputFactory secureFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }
}
