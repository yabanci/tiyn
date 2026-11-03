package kz.tiyn.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import kz.tiyn.support.TestImages;
import org.junit.jupiter.api.Test;

class ImageSanitizerTests {

    private static byte[] withExif(byte[] jpeg, int orientation) {
        byte[] tiff = {
            'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, 0, 0, 0, 0
        };
        byte[] header = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
        int length = 2 + header.length + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(header);
        out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }

    private static BufferedImage decode(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void jpegLosesItsExifBlockAndStillDecodes() throws Exception {
        byte[] original = withExif(TestImages.jpeg(20, 10), 1);
        assertThat(contains(original, "Exif")).isTrue();

        byte[] clean = ImageSanitizer.strip("image/jpeg", original);

        assertThat(contains(clean, "Exif")).isFalse();
        BufferedImage image = decode(clean);
        assertThat(image.getWidth()).isEqualTo(20);
        assertThat(image.getHeight()).isEqualTo(10);
    }

    @Test
    void exifRotationIsAppliedBeforeItIsDropped() throws Exception {
        byte[] original = withExif(TestImages.jpeg(20, 10), 6);

        BufferedImage image = decode(ImageSanitizer.strip("image/jpeg", original));

        assertThat(image.getWidth()).isEqualTo(10);
        assertThat(image.getHeight()).isEqualTo(20);
    }

    @Test
    void pngKeepsItsPixelsAndDimensions() throws Exception {
        BufferedImage image = decode(ImageSanitizer.strip("image/png", TestImages.png(12, 6)));

        assertThat(image.getWidth()).isEqualTo(12);
        assertThat(image.getHeight()).isEqualTo(6);
    }

    @Test
    void bytesThatAreNotAnImageAreRejected() {
        assertThatThrownBy(() -> ImageSanitizer.strip("image/png", new byte[] {1, 2, 3}))
                .isInstanceOf(InvalidMediaException.class);
    }

    @Test
    void aJpegDeclaredAsPngIsRejected() {
        assertThatThrownBy(() -> ImageSanitizer.strip("image/png", TestImages.jpeg(4, 4)))
                .isInstanceOf(InvalidMediaException.class);
    }

    @Test
    void truncatedImageIsRejected() {
        byte[] png = TestImages.png(40, 40);
        byte[] cut = java.util.Arrays.copyOf(png, png.length / 2);

        assertThatThrownBy(() -> ImageSanitizer.strip("image/png", cut)).isInstanceOf(InvalidMediaException.class);
    }

    @Test
    void webpIsNoLongerAccepted() {
        assertThatThrownBy(() -> MediaRules.requireAllowedType("image/webp")).isInstanceOf(InvalidMediaException.class);
    }

    private static byte[] flatPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void aTinyPngWithHostileDimensionsIsRejectedBeforeItIsDecoded() throws Exception {
        byte[] hostile = flatPng(9_000, 9_000);
        assertThat(hostile.length).isLessThan(MediaRules.MAX_BYTES);

        assertThatThrownBy(() -> ImageSanitizer.strip("image/png", hostile))
                .isInstanceOf(InvalidMediaException.class)
                .hasMessage("image too large");
    }

    @Test
    void anImageAtThePixelCeilingIsStillAccepted() throws Exception {
        byte[] atLimit = flatPng(4_000, 4_000);

        BufferedImage image = decode(ImageSanitizer.strip("image/png", atLimit));

        assertThat((long) image.getWidth() * image.getHeight()).isEqualTo(ImageSanitizer.MAX_PIXELS);
    }

    @Test
    void exifRotationOnAnImageAtTheCeilingStillSwapsDimensions() throws Exception {
        byte[] original = withExif(TestImages.jpeg(4_000, 2_000), 8);

        BufferedImage image = decode(ImageSanitizer.strip("image/jpeg", original));

        assertThat(image.getWidth()).isEqualTo(2_000);
        assertThat(image.getHeight()).isEqualTo(4_000);
    }

    @Test
    void whenEveryDecodeSlotIsTakenTheUploadFailsFastAsBusy() throws Exception {
        ImageSanitizer.GATE.acquire(ImageSanitizer.MAX_CONCURRENT_DECODES);
        try {
            long started = System.nanoTime();

            assertThatThrownBy(() -> ImageSanitizer.strip("image/png", TestImages.png(4, 4)))
                    .isInstanceOf(MediaBusyException.class);

            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .isLessThan(ImageSanitizer.GATE_WAIT_MILLIS + 1_000);
        } finally {
            ImageSanitizer.GATE.release(ImageSanitizer.MAX_CONCURRENT_DECODES);
        }
    }

    @Test
    void slotsAreReleasedAfterSuccessAndAfterFailure() {
        for (int i = 0; i < ImageSanitizer.MAX_CONCURRENT_DECODES * 3; i++) {
            ImageSanitizer.strip("image/png", TestImages.png(4, 4));
            assertThatThrownBy(() -> ImageSanitizer.strip("image/png", new byte[] {1, 2, 3}))
                    .isInstanceOf(InvalidMediaException.class);
        }

        assertThat(ImageSanitizer.GATE.availablePermits()).isEqualTo(ImageSanitizer.MAX_CONCURRENT_DECODES);
    }
}
