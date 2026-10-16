package com.krztechnologies.attend.common.media;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * Uploaded images (logo, enrolment photos and captures, security §7): PNG or JPEG only, recognised by their magic
 * bytes; the dimensions are checked before decoding (a small file cannot expand into a huge image); re-encoding strips
 * every metadata block.
 */
public final class Images {

    private Images() {}

    /** The accepted formats (never SVG, which can carry scripts). */
    public enum Format {
        PNG("png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}),
        JPEG("jpeg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        private final String imageIoName;
        private final String contentType;
        private final byte[] magic;

        Format(String imageIoName, String contentType, byte[] magic) {
            this.imageIoName = imageIoName;
            this.contentType = contentType;
            this.magic = magic;
        }

        public String contentType() {
            return contentType;
        }

        /** The format by the file's first bytes, never by its name or declared type. */
        public static Optional<Format> of(byte[] content) {
            for (Format format : values()) {
                if (startsWith(content, format.magic)) {
                    return Optional.of(format);
                }
            }
            return Optional.empty();
        }

        private static boolean startsWith(byte[] content, byte[] prefix) {
            if (content.length < prefix.length) {
                return false;
            }
            for (int i = 0; i < prefix.length; i++) {
                if (content[i] != prefix[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Why an image was refused. */
    public enum Problem {
        UNREADABLE,
        SIZE
    }

    /** An image that cannot be accepted; callers turn it into their own message. */
    public static final class ImageException extends RuntimeException {
        private final Problem problem;

        public ImageException(Problem problem) {
            super(problem.name());
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /**
     * Decodes the image after checking its width and height.
     *
     * @throws ImageException {@code SIZE} outside the bounds, {@code UNREADABLE} when it cannot be decoded
     */
    public static BufferedImage decode(byte[] content, Format format, int minSide, int maxSide) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            var readers = ImageIO.getImageReadersByFormatName(format.imageIoName);
            if (in == null || !readers.hasNext()) {
                throw new ImageException(Problem.UNREADABLE);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < minSide || height < minSide || width > maxSide || height > maxSide) {
                    throw new ImageException(Problem.SIZE);
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new ImageException(Problem.UNREADABLE);
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (ImageException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ImageException(Problem.UNREADABLE);
        }
    }

    /** Re-encodes without any metadata; JPEG gets no alpha channel (the JPEG writer refuses one). */
    public static byte[] encode(BufferedImage image, Format format, float jpegQuality) {
        BufferedImage source = image;
        if (format == Format.JPEG && image.getColorModel().hasAlpha()) {
            source = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = source.createGraphics();
            graphics.drawImage(image, 0, 0, Color.WHITE, null);
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer =
                ImageIO.getImageWritersByFormatName(format.imageIoName).next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (format == Format.JPEG) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(jpegQuality);
            }
            writer.write(null, new IIOImage(source, null, null), param);
        } catch (IOException | RuntimeException e) {
            throw new ImageException(Problem.UNREADABLE);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
