package com.videogameplatform.catalogue.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Selects featured media from metadata only. Hero and card contexts have distinct crop and
 * resolution needs (FEAT-003); no pixels, popularity values or game identities participate. */
public final class FeaturedMediaPolicy {

    /** The hero's wide art frame; hero candidates rank by how little of it they lose to cropping. */
    static final double HERO_RATIO = 8.0 / 3.0;

    /** A high-quality hero image covers a 1280-pixel-wide hero frame unscaled: 1280×480. */
    static final int HERO_MIN_WIDTH = 1280;

    static final int HERO_MIN_HEIGHT = 480;

    /** Below these dimensions an image is a thumbnail, never a featured presentation. */
    static final int MIN_WIDTH = 640;

    static final int MIN_HEIGHT = 360;

    static final int LOGO_MIN_WIDTH = 160;

    static final int LOGO_MIN_HEIGHT = 40;

    private FeaturedMediaPolicy() {}

    /** The provider-independent kind of a candidate image. */
    public enum ImageKind {
        ARTWORK,
        SCREENSHOT,
        LOGO
    }

    /** How a frame shows an image: cropped to fill it, or whole inside a designed treatment. */
    public enum Presentation {
        FILL,
        CONTAIN
    }

    /** The metadata the policy reads; implementations carry their own identity. */
    public interface Image {

        /** Opaque, stable source identity used only to break equal metadata ties. */
        String reference();

        ImageKind kind();

        int width();

        int height();

        boolean transparent();

        boolean animated();

        /** Provider-declared key art that includes the title; unknown metadata remains false. */
        default boolean titleArtwork() {
            return false;
        }
    }

    /**
     * Hero: the hero sets the game's title itself, so it prefers art without one. In order:
     * high-quality artwork without a provider-declared title, high-quality screenshot,
     * high-quality title artwork, then the same three among the other accepted landscape media.
     * Within a step: least crop to the 8:3 hero frame, then larger pixel area, then the stable
     * reference.
     */
    public static <T extends Image> Optional<T> featuredImage(List<T> candidates) {
        Predicate<Image> untitledArtwork =
                image -> image.kind() == ImageKind.ARTWORK && !image.titleArtwork();
        Predicate<Image> screenshot = image -> image.kind() == ImageKind.SCREENSHOT;
        Predicate<Image> titleArtwork =
                image -> image.kind() == ImageKind.ARTWORK && image.titleArtwork();
        return best(candidates, untitledArtwork.and(FeaturedMediaPolicy::heroQuality))
                .or(() -> best(candidates, screenshot.and(FeaturedMediaPolicy::heroQuality)))
                .or(() -> best(candidates, titleArtwork.and(FeaturedMediaPolicy::heroQuality)))
                .or(() -> best(candidates, untitledArtwork.and(FeaturedMediaPolicy::presentable)))
                .or(() -> best(candidates, screenshot.and(FeaturedMediaPolicy::presentable)))
                .or(() -> best(candidates, titleArtwork.and(FeaturedMediaPolicy::presentable)));
    }

    /**
     * Card: at least 640x360, opaque and still; retain at least 80% at the 16:9.4 card ratio.
     * Prefer less crop, then usable area capped at the 1280x720 delivery ceiling. Provider-declared
     * title key art wins an equal-suitability composition tie, then screenshots, then ordinary
     * artwork. The stable reference breaks the final tie.
     */
    public static <T extends Image> Optional<T> cardImage(List<T> candidates) {
        return candidates.stream()
                .filter(
                        image ->
                                media(image)
                                        && !image.animated()
                                        && cardSuitable(
                                                image.width(), image.height(), image.transparent()))
                .min(
                        Comparator.<T>comparingDouble(image -> cropLoss(image, 16.0 / 9.4))
                                .thenComparing(
                                        Comparator.<T>comparingLong(
                                                        image ->
                                                                (long) Math.min(image.width(), 1280)
                                                                        * Math.min(
                                                                                image.height(),
                                                                                720))
                                                .reversed())
                                .thenComparingInt(
                                        image ->
                                                image.kind() == ImageKind.ARTWORK
                                                                && image.titleArtwork()
                                                        ? 0
                                                        : image.kind() == ImageKind.SCREENSHOT
                                                                ? 1
                                                                : 2)
                                .thenComparing(Image::reference));
    }

    public static boolean landscape(int width, int height, boolean transparent) {
        return !transparent
                && width >= MIN_WIDTH
                && height >= MIN_HEIGHT
                && 2L * width >= 3L * height
                && 5L * width <= 16L * height;
    }

    public static boolean cardSuitable(int width, int height, boolean transparent) {
        if (!landscape(width, height, transparent)) return false;
        double ratio = (double) width / height;
        double target = 16.0 / 9.4;
        return Math.min(ratio / target, target / ratio) >= 0.8;
    }

    private static boolean media(Image image) {
        return image.kind() == ImageKind.ARTWORK || image.kind() == ImageKind.SCREENSHOT;
    }

    private static double cropLoss(Image image, double target) {
        double ratio = (double) image.width() / image.height();
        return 1.0 - Math.min(ratio / target, target / ratio);
    }

    private static <T extends Image> Optional<T> best(List<T> candidates, Predicate<Image> step) {
        return candidates.stream()
                .filter(step)
                .min(
                        Comparator.<T>comparingDouble(image -> cropLoss(image, HERO_RATIO))
                                .thenComparing(
                                        Comparator.<T>comparingLong(
                                                        image ->
                                                                (long) image.width()
                                                                        * image.height())
                                                .reversed())
                                .thenComparing(Image::reference));
    }

    /** The title logo among logos, if one can stand over artwork without a box around it. */
    public static <T extends Image> Optional<T> logo(List<T> candidates) {
        return first(
                candidates,
                ImageKind.LOGO,
                image ->
                        image.transparent()
                                && !image.animated()
                                && image.width() >= LOGO_MIN_WIDTH
                                && image.height() >= LOGO_MIN_HEIGHT);
    }

    /**
     * An opaque image at least 1.3 times wider than tall can be cropped to a landscape frame; any
     * other image is shown whole, because cropping it would cut the subject out or show its empty
     * transparent areas.
     */
    public static Presentation presentation(int width, int height, boolean transparent) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Image dimensions must be positive");
        }
        return !transparent && 10L * width >= 13L * height
                ? Presentation.FILL
                : Presentation.CONTAIN;
    }

    private static boolean heroQuality(Image image) {
        return presentable(image)
                && image.width() >= HERO_MIN_WIDTH
                && image.height() >= HERO_MIN_HEIGHT;
    }

    private static boolean presentable(Image image) {
        return media(image)
                && !image.animated()
                && landscape(image.width(), image.height(), image.transparent());
    }

    private static <T extends Image> Optional<T> first(
            List<T> candidates, ImageKind kind, Predicate<Image> suitable) {
        return candidates.stream()
                .filter(image -> image.kind() == kind && suitable.test(image))
                .findFirst();
    }
}
