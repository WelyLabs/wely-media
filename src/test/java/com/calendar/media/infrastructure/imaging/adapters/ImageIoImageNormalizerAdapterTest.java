package com.calendar.media.infrastructure.imaging.adapters;

import com.calendar.media.domain.models.MediaProfile;
import com.calendar.media.domain.models.NormalizedImage;
import com.calendar.media.exception.BusinessErrorCode;
import com.calendar.media.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.test.StepVerifier;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ImageIoImageNormalizerAdapterTest {

    private final ImageIoImageNormalizerAdapter normalizer = new ImageIoImageNormalizerAdapter();

    private static byte[] image(int width, int height, String format) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedImage decode(NormalizedImage image) {
        try {
            return ImageIO.read(new ByteArrayInputStream(image.content()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String decodedSize(NormalizedImage image) {
        BufferedImage decoded = decode(image);
        return decoded.getWidth() + "x" + decoded.getHeight();
    }

    private static boolean invalid(Throwable error) {
        return error instanceof BusinessException business && business.getErrorCode() == BusinessErrorCode.MEDIA_INVALID;
    }

    @Test
    @DisplayName("an avatar is the centered square of the picture, 512 px a side")
    void normalize_shouldCropAnAvatarToTheCenteredSquare() {
        // Red left third, blue middle third, green right third: only the middle may survive.
        BufferedImage source = new BufferedImage(1500, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = source.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 500, 500);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(500, 0, 500, 500);
        graphics.setColor(Color.GREEN);
        graphics.fillRect(1000, 0, 500, 500);
        graphics.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            ImageIO.write(source, "png", png);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        StepVerifier.create(normalizer.normalize(png.toByteArray(), MediaProfile.AVATAR))
                .assertNext(avatar -> {
                    assertThat(avatar.width()).isEqualTo(ImageIoImageNormalizerAdapter.AVATAR_SIDE);
                    assertThat(avatar.height()).isEqualTo(ImageIoImageNormalizerAdapter.AVATAR_SIDE);
                    BufferedImage decoded = decode(avatar);
                    assertThat(decoded.getWidth()).isEqualTo(512);
                    Color left = new Color(decoded.getRGB(10, 256));
                    Color right = new Color(decoded.getRGB(500, 256));
                    assertThat(left.getBlue()).isGreaterThan(200);
                    assertThat(left.getRed()).isLessThan(60);
                    assertThat(right.getBlue()).isGreaterThan(200);
                    assertThat(right.getGreen()).isLessThan(60);
                })
                .verifyComplete();
    }

    @Test
    void normalize_shouldBoundAPhotosLongestSide_keepingProportions() {
        StepVerifier.create(normalizer.normalize(image(4000, 3000, "png"), MediaProfile.PHOTO))
                .assertNext(photo -> {
                    assertThat(photo.width()).isEqualTo(1600);
                    assertThat(photo.height()).isEqualTo(1200);
                    assertThat(photo.contentType()).isEqualTo("image/jpeg");
                    assertThat(decodedSize(photo)).isEqualTo("1600x1200");
                })
                .verifyComplete();
    }

    @Test
    void normalize_shouldBoundAPortraitPhotoByItsHeight() {
        StepVerifier.create(normalizer.normalize(image(900, 2400, "jpg"), MediaProfile.PHOTO))
                .assertNext(photo -> assertThat(decodedSize(photo)).isEqualTo("600x1600"))
                .verifyComplete();
    }

    @Test
    @DisplayName("a cover is bounded by its width only: a tall cover keeps its height")
    void normalize_shouldBoundACoverByItsWidth() {
        StepVerifier.create(normalizer.normalize(image(3200, 1000, "png"), MediaProfile.COVER))
                .assertNext(cover -> assertThat(decodedSize(cover)).isEqualTo("1600x500"))
                .verifyComplete();
        StepVerifier.create(normalizer.normalize(image(1000, 2500, "png"), MediaProfile.COVER))
                .assertNext(cover -> assertThat(decodedSize(cover)).isEqualTo("1000x2500"))
                .verifyComplete();
    }

    @Test
    void normalize_shouldBoundAReceiptsLongestSideAt2000() {
        StepVerifier.create(normalizer.normalize(image(3000, 6000, "png"), MediaProfile.RECEIPT))
                .assertNext(receipt -> assertThat(decodedSize(receipt)).isEqualTo("1000x2000"))
                .verifyComplete();
    }

    @ParameterizedTest
    @EnumSource(value = MediaProfile.class, names = {"PHOTO", "COVER", "RECEIPT"})
    void normalize_shouldNeverEnlargeASmallPicture(MediaProfile profile) {
        StepVerifier.create(normalizer.normalize(image(320, 240, "png"), profile))
                .assertNext(small -> assertThat(decodedSize(small)).isEqualTo("320x240"))
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gif", "bmp", "jpg", "png"})
    void normalize_shouldAcceptEveryReadableFormat_andAnswerAJpeg(String format) {
        StepVerifier.create(normalizer.normalize(image(40, 30, format), MediaProfile.PHOTO))
                .assertNext(out -> {
                    assertThat(out.content()[0] & 0xFF).isEqualTo(0xFF);
                    assertThat(out.content()[1] & 0xFF).isEqualTo(0xD8);
                })
                .verifyComplete();
    }

    @Test
    void normalize_shouldRefuseWhatIsNotAnImage() {
        StepVerifier.create(normalizer.normalize("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8), MediaProfile.PHOTO))
                .expectErrorMatches(ImageIoImageNormalizerAdapterTest::invalid)
                .verify();
    }

    @Test
    void normalize_shouldRefuseAnEmptyUpload() {
        StepVerifier.create(normalizer.normalize(new byte[0], MediaProfile.AVATAR))
                .expectErrorMatches(ImageIoImageNormalizerAdapterTest::invalid)
                .verify();
    }

    @Test
    void normalize_shouldRefuseATruncatedImage() {
        byte[] png = image(300, 300, "png");

        StepVerifier.create(normalizer.normalize(Arrays.copyOf(png, png.length / 3), MediaProfile.PHOTO))
                .expectErrorMatches(ImageIoImageNormalizerAdapterTest::invalid)
                .verify();
    }

    @Test
    @DisplayName("a picture too big to hold in memory is refused from its header, before its pixels are decoded")
    void normalize_shouldRefuseAHugeImageFromItsHeader() {
        StepVerifier.create(normalizer.normalize(image(ImageIoImageNormalizerAdapter.MAX_SOURCE_SIDE + 1, 10, "png"), MediaProfile.PHOTO))
                .expectErrorMatches(ImageIoImageNormalizerAdapterTest::invalid)
                .verify();
        // 7000 x 7000 is under the side limit but 49 megapixels.
        StepVerifier.create(normalizer.normalize(image(7000, 7000, "png"), MediaProfile.RECEIPT))
                .expectErrorMatches(ImageIoImageNormalizerAdapterTest::invalid)
                .verify();
    }

    @Test
    @DisplayName("what is stored is a fresh JPEG: a phone's EXIF block, GPS included, does not survive")
    void normalize_shouldDropTheOriginalsMetadata() {
        byte[] jpeg = image(200, 100, "jpg");
        byte[] exif = "Exif\0\0GPS-LATITUDE-45.76".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream withExif = new ByteArrayOutputStream();
        withExif.write(jpeg, 0, 2);
        withExif.write(0xFF);
        withExif.write(0xE1);
        withExif.write((exif.length + 2) >> 8);
        withExif.write((exif.length + 2) & 0xFF);
        withExif.write(exif, 0, exif.length);
        withExif.write(jpeg, 2, jpeg.length - 2);

        StepVerifier.create(normalizer.normalize(withExif.toByteArray(), MediaProfile.PHOTO))
                .assertNext(photo -> assertThat(new String(photo.content(), StandardCharsets.ISO_8859_1))
                        .doesNotContain("GPS-LATITUDE"))
                .verifyComplete();
    }
}
