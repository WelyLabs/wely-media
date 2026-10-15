package com.calendar.media.infrastructure.storage.listing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListBucketResultParserTest {

    private static byte[] listing(String contents, boolean truncated, String nextToken) {
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Name>wely-avatars</Name>
                  <Prefix>avatars/u1/</Prefix>
                  <KeyCount>2</KeyCount>
                  <MaxKeys>200</MaxKeys>
                  <IsTruncated>%s</IsTruncated>
                  %s
                  %s
                </ListBucketResult>""".formatted(truncated, nextToken == null ? ""
                : "<NextContinuationToken>" + nextToken + "</NextContinuationToken>", contents))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String object(String key) {
        return "<Contents><Key>" + key + "</Key><LastModified>2026-10-15T10:00:00.000Z</LastModified>"
                + "<ETag>\"e\"</ETag><Size>3</Size><StorageClass>STANDARD</StorageClass></Contents>";
    }

    @Test
    @DisplayName("the keys come out in the listing's order, XML escapes undone")
    void parse_shouldReadTheKeysInOrder() {
        ListBucketPage page = ListBucketResultParser.parse(listing(
                object("avatars/u1/b.jpg") + object("avatars/u1/a&amp;b.jpg"), false, null));

        assertThat(page.keys()).containsExactly("avatars/u1/b.jpg", "avatars/u1/a&b.jpg");
        assertThat(page.nextToken()).isNull();
    }

    @Test
    @DisplayName("a truncated listing gives the token of the next page")
    void parse_shouldReadTheNextToken_whenTruncated() {
        ListBucketPage page = ListBucketResultParser.parse(listing(object("avatars/u1/a.jpg"), true, "dG9rZW4="));

        assertThat(page.nextToken()).isEqualTo("dG9rZW4=");
    }

    @Test
    @DisplayName("a truncated listing without a token is refused: the rest of the folder could not be read")
    void parse_shouldFail_whenTruncatedWithoutToken() {
        byte[] noToken = listing(object("avatars/u1/a.jpg"), true, null);
        byte[] blankToken = listing(object("avatars/u1/a.jpg"), true, " ");

        assertThatThrownBy(() -> ListBucketResultParser.parse(noToken)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ListBucketResultParser.parse(blankToken)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a token on a listing that is not truncated is ignored: it is the last page")
    void parse_shouldIgnoreTheToken_whenNotTruncated() {
        ListBucketPage page = ListBucketResultParser.parse(listing(object("avatars/u1/a.jpg"), false, "dG9rZW4="));

        assertThat(page.nextToken()).isNull();
    }

    @Test
    @DisplayName("a listing with no object is an empty last page")
    void parse_shouldReadAnEmptyListing() {
        ListBucketPage page = ListBucketResultParser.parse(listing("", false, null));

        assertThat(page.keys()).isEmpty();
        assertThat(page.nextToken()).isNull();
    }

    @Test
    @DisplayName("a document type declaration is refused, so no entity is expanded")
    void parse_shouldRefuseADocumentTypeDeclaration() {
        byte[] xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE ListBucketResult [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <ListBucketResult><Contents><Key>&secret;</Key></Contents></ListBucketResult>"""
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ListBucketResultParser.parse(xxe)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("something that is not XML is refused")
    void parse_shouldFail_onMalformedXml() {
        assertThatThrownBy(() -> ListBucketResultParser.parse("<ListBucketResult><Contents>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ListBucketResultParser.parse("not xml".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("well-formed XML that is not a listing, such as a proxy's error page, is refused")
    void parse_shouldRefuseADocumentThatIsNotAListing() {
        assertThatThrownBy(() -> ListBucketResultParser.parse("<html><body>Bad gateway</body></html>"
                .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ListBucketResultParser.parse(("<Error><Code>AccessDenied</Code>"
                + "<Key>avatars/u1/a.jpg</Key></Error>").getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class);
    }
}
