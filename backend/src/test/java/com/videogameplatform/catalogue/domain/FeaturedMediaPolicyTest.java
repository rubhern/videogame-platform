package com.videogameplatform.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy.ImageKind;
import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy.Presentation;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeaturedMediaPolicyTest {

    @Test
    void aWideArtworkComesBeforeAnEarlierWideScreenshot() {
        var screenshot = image("shot", ImageKind.SCREENSHOT, 1920, 1080);
        var artwork = image("art", ImageKind.ARTWORK, 2560, 1440);

        assertThat(FeaturedMediaPolicy.featuredImage(List.of(screenshot, artwork)))
                .contains(artwork);
    }

    @Test
    void aWideScreenshotComesBeforeAnArtworkThatIsNotWide() {
        var tallArtwork = image("tall", ImageKind.ARTWORK, 1200, 1800);
        var screenshot = image("shot", ImageKind.SCREENSHOT, 1920, 1080);

        assertThat(FeaturedMediaPolicy.featuredImage(List.of(tallArtwork, screenshot)))
                .contains(screenshot);
    }

    @Test
    void lowResolutionLandscapeIsAcceptedButPortraitAndUltrawideAreNot() {
        var lowResolution = image("low", ImageKind.SCREENSHOT, 800, 450);
        var portrait = image("portrait", ImageKind.ARTWORK, 1440, 1920);
        var ultrawide = image("banner", ImageKind.ARTWORK, 3840, 900);
        assertThat(FeaturedMediaPolicy.featuredImage(List.of(portrait, ultrawide, lowResolution)))
                .contains(lowResolution);
        assertThat(FeaturedMediaPolicy.featuredImage(List.of(portrait, ultrawide))).isEmpty();
    }

    @Test
    void heroPrefersTheWideHeroFrameThenHigherResolutionThenStableIdentity() {
        var keyArt = image("keyart", ImageKind.ARTWORK, 3840, 2160);
        var smallBanner = image("small", ImageKind.ARTWORK, 1920, 620);
        var banner = image("banner", ImageKind.ARTWORK, 3840, 1240);
        var tie = image("z-tie", ImageKind.ARTWORK, 3840, 1240);
        assertThat(FeaturedMediaPolicy.featuredImage(List.of(keyArt, smallBanner, tie, banner)))
                .contains(banner);
        assertThat(FeaturedMediaPolicy.featuredImage(List.of(tie, banner, smallBanner, keyArt)))
                .contains(banner);
        // Without a banner, a 2:1 image loses less of the 8:3 hero frame than a 16:9 one.
        var twoByOne = image("two-by-one", ImageKind.ARTWORK, 2560, 1280);
        assertThat(FeaturedMediaPolicy.featuredImage(List.of(keyArt, twoByOne))).contains(twoByOne);
    }

    @Test
    void theHeroSetsItsOwnTitleSoDeclaredTitleArtworkYieldsToAScreenshot() {
        var titled = new Candidate("titled", ImageKind.ARTWORK, 3840, 1240, false, false, true);
        var shot = image("shot", ImageKind.SCREENSHOT, 1920, 1080);
        assertThat(FeaturedMediaPolicy.featuredImage(List.of(titled, shot))).contains(shot);
        var untitled = image("untitled", ImageKind.ARTWORK, 1600, 900);
        assertThat(FeaturedMediaPolicy.featuredImage(List.of(titled, shot, untitled)))
                .contains(untitled);
    }

    @Test
    void highQualityTitleArtworkStillPrecedesLowerQualityMediaInTheSameOrder() {
        var titled = new Candidate("titled", ImageKind.ARTWORK, 1920, 1080, false, false, true);
        var smallArtwork = image("small-art", ImageKind.ARTWORK, 1200, 675);
        var smallShot = image("small-shot", ImageKind.SCREENSHOT, 1200, 675);
        var smallTitled =
                new Candidate("small-titled", ImageKind.ARTWORK, 1200, 675, false, false, true);
        assertThat(FeaturedMediaPolicy.featuredImage(List.of(smallShot, smallArtwork, titled)))
                .contains(titled);
        assertThat(FeaturedMediaPolicy.featuredImage(List.of(smallTitled, smallShot, smallArtwork)))
                .contains(smallArtwork);
        assertThat(FeaturedMediaPolicy.featuredImage(List.of(smallTitled, smallShot)))
                .contains(smallShot);
        assertThat(FeaturedMediaPolicy.featuredImage(List.of(smallTitled))).contains(smallTitled);
    }

    @Test
    void aBannerThatCoversTheHeroFrameIsHighQualityBelowSevenHundredTwentyPixels() {
        var banner = image("banner", ImageKind.ARTWORK, 1920, 620);
        var shot = image("shot", ImageKind.SCREENSHOT, 1920, 1080);
        assertThat(FeaturedMediaPolicy.featuredImage(List.of(shot, banner))).contains(banner);
        // 1280×479 cannot cover the 1280×480 hero frame unscaled, so the screenshot wins.
        var shortBanner = image("short", ImageKind.ARTWORK, 1280, 479);
        assertThat(FeaturedMediaPolicy.featuredImage(List.of(shortBanner, shot))).contains(shot);
    }

    @Test
    void cardsPreferLessCropAcrossTypesThenAdequateDeliveryResolution() {
        var banner = image("banner", ImageKind.ARTWORK, 3840, 1240);
        var small = image("small", ImageKind.SCREENSHOT, 640, 360);
        var shot = image("shot", ImageKind.SCREENSHOT, 1920, 1080);
        assertThat(FeaturedMediaPolicy.cardImage(List.of(banner, small, shot))).contains(shot);
        var exactFrame = image("exact", ImageKind.ARTWORK, 1600, 940);
        assertThat(FeaturedMediaPolicy.cardImage(List.of(shot, exactFrame))).contains(exactFrame);
    }

    @Test
    void cardsPreferScreenshotsOnEqualFitAndDeliveredResolutionWithoutOversizedAssetBias() {
        var texture = image("texture", ImageKind.ARTWORK, 3840, 2160);
        var shot = image("shot", ImageKind.SCREENSHOT, 1920, 1080);
        var oversizedShot = image("z-shot", ImageKind.SCREENSHOT, 3840, 2160);
        assertThat(FeaturedMediaPolicy.cardImage(List.of(texture, oversizedShot, shot)))
                .contains(shot);
        assertThat(FeaturedMediaPolicy.cardImage(List.of(shot, oversizedShot, texture)))
                .contains(shot);
    }

    @Test
    void providerDeclaredTitleKeyArtPreservesGoodCardsOnlyOnEqualSuitability() {
        var keyArt = new Candidate("key", ImageKind.ARTWORK, 3840, 2160, false, false, true);
        var shot = image("shot", ImageKind.SCREENSHOT, 1920, 1080);
        var texture = image("texture", ImageKind.ARTWORK, 3840, 2160);
        assertThat(FeaturedMediaPolicy.cardImage(List.of(texture, shot, keyArt))).contains(keyArt);
        var croppedKeyArt =
                new Candidate("cropped", ImageKind.ARTWORK, 2000, 1000, false, false, true);
        assertThat(FeaturedMediaPolicy.cardImage(List.of(croppedKeyArt, shot))).contains(shot);
    }

    @Test
    void cardsRejectDestructiveCropLowResolutionTransparencyAndAnimation() {
        var banner = image("banner", ImageKind.ARTWORK, 3840, 1240);
        var portrait = image("portrait", ImageKind.ARTWORK, 1200, 1800);
        var tiny = image("tiny", ImageKind.SCREENSHOT, 639, 360);
        var shortImage = image("short", ImageKind.ARTWORK, 640, 359);
        var alpha = new Candidate("alpha", ImageKind.ARTWORK, 1920, 1080, true, false);
        var animated = new Candidate("animated", ImageKind.SCREENSHOT, 1920, 1080, false, true);
        assertThat(
                        FeaturedMediaPolicy.cardImage(
                                List.of(banner, portrait, tiny, shortImage, alpha, animated)))
                .isEmpty();
        assertThat(FeaturedMediaPolicy.cardImage(List.<Candidate>of())).isEmpty();
    }

    @Test
    void anAnimatedOrThumbnailImageIsNeverFeatured() {
        var animated = new Candidate("gif", ImageKind.ARTWORK, 1920, 1080, false, true);
        var thumbnail = image("thumb", ImageKind.SCREENSHOT, 319, 180);
        var logo = new Candidate("logo", ImageKind.LOGO, 1920, 1080, false, false);

        assertThat(FeaturedMediaPolicy.featuredImage(List.of(animated, thumbnail, logo))).isEmpty();
        assertThat(FeaturedMediaPolicy.featuredImage(List.<Candidate>of())).isEmpty();
    }

    @Test
    void onlyAnOpaqueImageAtLeastThirteenTenthsWideIsCroppedToFill() {
        assertThat(FeaturedMediaPolicy.presentation(1920, 1080, false))
                .isEqualTo(Presentation.FILL);
        assertThat(FeaturedMediaPolicy.presentation(1300, 1000, false))
                .isEqualTo(Presentation.FILL);
        assertThat(FeaturedMediaPolicy.presentation(1299, 1000, false))
                .isEqualTo(Presentation.CONTAIN);
        assertThat(FeaturedMediaPolicy.presentation(1000, 1500, false))
                .isEqualTo(Presentation.CONTAIN);
        assertThat(FeaturedMediaPolicy.presentation(1920, 1080, true))
                .isEqualTo(Presentation.CONTAIN);
        assertThatThrownBy(() -> FeaturedMediaPolicy.presentation(0, 1080, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTitleLogoMustBeTransparentStillAndLegible() {
        var boxed = new Candidate("boxed", ImageKind.LOGO, 800, 300, false, false);
        var animated = new Candidate("animated", ImageKind.LOGO, 800, 300, true, true);
        var tiny = new Candidate("tiny", ImageKind.LOGO, 159, 60, true, false);
        var clear = new Candidate("clear", ImageKind.LOGO, 800, 300, true, false);
        var later = new Candidate("later", ImageKind.LOGO, 1600, 600, true, false);

        assertThat(FeaturedMediaPolicy.logo(List.of(boxed, animated, tiny, clear, later)))
                .contains(clear);
        assertThat(FeaturedMediaPolicy.logo(List.of(boxed, animated, tiny))).isEmpty();
    }

    private static Candidate image(String reference, ImageKind kind, int width, int height) {
        return new Candidate(reference, kind, width, height, false, false);
    }

    private record Candidate(
            String reference,
            ImageKind kind,
            int width,
            int height,
            boolean transparent,
            boolean animated,
            boolean titleArtwork)
            implements FeaturedMediaPolicy.Image {
        Candidate(
                String reference,
                ImageKind kind,
                int width,
                int height,
                boolean transparent,
                boolean animated) {
            this(reference, kind, width, height, transparent, animated, false);
        }
    }
}
