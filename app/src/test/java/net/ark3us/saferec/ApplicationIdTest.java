package net.ark3us.saferec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class ApplicationIdTest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    // Registered on the Android Developer Console with the release key: another id would not
    // install on certified phones, and Drive sign-in matches its OAuth clients by this id
    @Test public void applicationIdIsTheRegisteredOne() {
        assertEquals("net.ark3us.saferec.app", BuildConfig.APPLICATION_ID);
    }

    // shortcuts.xml cannot use ${applicationId}: its targetPackage is written by hand
    @Test public void shortcutsTargetTheApplicationId() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder()
                .parse(new File("src/main/res/xml/shortcuts.xml"));
        NodeList intents = doc.getElementsByTagName("intent");
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < intents.getLength(); i++) {
            targets.add(((Element) intents.item(i)).getAttributeNS(ANDROID_NS, "targetPackage"));
        }
        assertFalse(targets.isEmpty());
        for (String target : targets) assertEquals(BuildConfig.APPLICATION_ID, target);
    }
}
