package kz.tiyn.media;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

final class ImageSanitizer {

    static final long MAX_PIXELS = 16_000_000L;
    static final int MAX_CONCURRENT_DECODES = 2;
    static final long GATE_WAIT_MILLIS = 1_000L;
    static final Semaphore GATE = new Semaphore(MAX_CONCURRENT_DECODES);
    private static final float JPEG_QUALITY = 0.9f;

    private ImageSanitizer() {}

    static byte[] strip(String contentType, byte[] bytes) {
        acquireGate();
        try {
            return decodeAndEncode(contentType, bytes);
        } finally {
            GATE.release();
        }
    }

    private static void acquireGate() {
        try {
            if (!GATE.tryAcquire(GATE_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new MediaBusyException();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaBusyException();
        }
    }

    private static byte[] decodeAndEncode(String contentType, byte[] bytes) {
        String format = contentType.equals("image/png") ? "png" : "jpeg";
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new InvalidMediaException("not an image");
            }
            ImageReader reader = readers.next();
            try {
                if (!reader.getFormatName().equalsIgnoreCase(format)) {
                    throw new InvalidMediaException("content does not match type");
                }
                reader.setInput(in, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    throw new InvalidMediaException("image too large");
                }
                BufferedImage image = reader.read(0);
                if (format.equals("jpeg")) {
                    image = oriented(image, ExifOrientation.read(bytes));
                }
                return encode(image, format);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof InvalidMediaException invalid) {
                throw invalid;
            }
            throw new InvalidMediaException("unreadable image");
        }
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        BufferedImage target = image;
        if (format.equals("jpeg") && image.getColorModel().hasAlpha()) {
            target = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = target.createGraphics();
            g.drawImage(image, 0, 0, java.awt.Color.WHITE, null);
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (format.equals("jpeg")) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(target, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static BufferedImage oriented(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> {
                t.translate(w, 0);
                t.scale(-1, 1);
            }
            case 3 -> {
                t.translate(w, h);
                t.rotate(Math.PI);
            }
            case 4 -> {
                t.translate(0, h);
                t.scale(1, -1);
            }
            case 5 -> {
                t.rotate(Math.PI / 2);
                t.scale(1, -1);
            }
            case 6 -> {
                t.translate(h, 0);
                t.rotate(Math.PI / 2);
            }
            case 7 -> {
                t.translate(h, w);
                t.rotate(Math.PI / 2);
                t.scale(-1, 1);
            }
            default -> {
                t.translate(0, w);
                t.rotate(-Math.PI / 2);
            }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(image, t, null);
        g.dispose();
        return out;
    }
}
