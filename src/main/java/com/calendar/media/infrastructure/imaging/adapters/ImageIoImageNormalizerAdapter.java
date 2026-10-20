package com.calendar.media.infrastructure.imaging.adapters;

import com.calendar.media.domain.models.MediaProfile;
import com.calendar.media.domain.models.NormalizedImage;
import com.calendar.media.domain.ports.ImageNormalizer;
import com.calendar.media.exception.BusinessErrorCode;
import com.calendar.media.exception.BusinessException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Normalizes pictures with the JDK's ImageIO: JPEG, PNG, GIF and BMP in, a JPEG out, shaped by
 * the {@link MediaProfile}.
 *
 * <p>Decoding is the type check, and the size is read from the header before the pixels, so a
 * small file claiming a huge image is refused without being decoded. The picture is drawn anew on
 * a white canvas and encoded as JPEG, which carries none of the original's metadata — the GPS
 * position included. ImageIO does not apply the EXIF orientation: the web app draws the picture
 * on a canvas before sending, which does.
 *
 * <p>ImageIO blocks: the work runs on boundedElastic, off the event loop.
 */
@Component
public class ImageIoImageNormalizerAdapter implements ImageNormalizer {

    static final int AVATAR_SIDE = 512;
    static final int PHOTO_MAX_SIDE = 1600;
    static final int COVER_MAX_WIDTH = 1600;
    static final int RECEIPT_MAX_SIDE = 2000;
    static final int MAX_SOURCE_SIDE = 8000;
    /** Decoded, a picture takes 4 bytes per pixel: 40 megapixels is 160 MB, the most worth holding. */
    static final long MAX_SOURCE_PIXELS = 40_000_000L;
    static final float QUALITY = 0.85f;
    static final String CONTENT_TYPE = "image/jpeg";

    @Override
    public Mono<NormalizedImage> normalize(byte[] upload, MediaProfile profile) {
        return Mono.fromCallable(() -> {
                    BufferedImage image = reshape(read(upload), profile);
                    return new NormalizedImage(encode(image), CONTENT_TYPE, image.getWidth(), image.getHeight());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static BufferedImage read(byte[] upload) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(upload))) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                throw invalid();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE || (long) width * height > MAX_SOURCE_PIXELS) {
                    throw invalid();
                }
                return reader.read(0);
            } catch (IOException | RuntimeException unreadable) {
                if (unreadable instanceof BusinessException business) {
                    throw business;
                }
                throw invalid();
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage reshape(BufferedImage source, MediaProfile profile) {
        int width = source.getWidth();
        int height = source.getHeight();
        return switch (profile) {
            case AVATAR -> {
                int side = Math.min(width, height);
                int x = (width - side) / 2;
                int y = (height - side) / 2;
                yield draw(source, x, y, side, side, AVATAR_SIDE, AVATAR_SIDE);
            }
            case PHOTO -> scaled(source, (double) PHOTO_MAX_SIDE / Math.max(width, height));
            case COVER -> scaled(source, (double) COVER_MAX_WIDTH / width);
            case RECEIPT -> scaled(source, (double) RECEIPT_MAX_SIDE / Math.max(width, height));
        };
    }

    /** Scaled down by {@code ratio}, never up. */
    private static BufferedImage scaled(BufferedImage source, double ratio) {
        double scale = Math.min(1.0, ratio);
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        return draw(source, 0, 0, source.getWidth(), source.getHeight(), width, height);
    }

    /** The source region drawn on an opaque canvas, since JPEG has no transparency. */
    private static BufferedImage draw(BufferedImage source, int x, int y, int sourceWidth, int sourceHeight,
                                      int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, width, height, x, y, x + sourceWidth, y + sourceHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(QUALITY);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static BusinessException invalid() {
        return new BusinessException(BusinessErrorCode.MEDIA_INVALID);
    }
}
