package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ImagePathsTest {

    @Test
    void installedIconsAreOneLevelUpFromTheImageRoot() {
        String root = ImagePaths.imageRoot("/mnt/app/eso/hmi/lsd/mibsdk");
        assertEquals("/mnt/app/eso/hmi/lsd/images", root);
        assertEquals("../mibsdk/icons/hello-menu/0123456789abcdef.png",
                ImagePaths.relative(root, "/mnt/app/eso/hmi/lsd/mibsdk/icons/hello-menu/0123456789abcdef.png"));
        assertEquals("../mibsdk/icons-sdk/0123456789abcdef.png",
                ImagePaths.relative(root, "/mnt/app/eso/hmi/lsd/mibsdk/icons-sdk/0123456789abcdef.png"));
        assertEquals(root, ImagePaths.imageRoot("/mnt/app/eso/hmi/lsd/mibsdk/"));
    }

    @Test
    void relativeClimbsToTheCommonPrefix() {
        assertEquals("x.png", ImagePaths.relative("/a/b", "/a/b/x.png"));
        assertEquals("../../c/x.png", ImagePaths.relative("/a/b/d", "/a/c/x.png"));
        assertEquals("../../x.png", ImagePaths.relative("/a/b", "/x.png"));
        assertEquals("../b2/x.png", ImagePaths.relative("/a/b", "/a/b2/x.png"), "segments, not characters");
        assertNull(ImagePaths.relative("/a/b", "rel/x.png"));
        assertNull(ImagePaths.relative("rel", "/x.png"));
    }
}
