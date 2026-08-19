import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class ConfigureMavenSettings {
    private static final String SERVER_ID = "github";
    private static final String PROFILE_ID = "subauth-github-packages";
    private static final String SETTINGS_NAMESPACE = "http://maven.apache.org/SETTINGS/1.2.0";
    private static final DateTimeFormatter BACKUP_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private ConfigureMavenSettings() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: ConfigureMavenSettings <settings.xml> <github-user> <repository-url>");
        }

        String token = System.getenv("SUBAUTH_SETUP_GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("SUBAUTH_SETUP_GITHUB_TOKEN is required");
        }

        Path settings = Path.of(args[0]).toAbsolutePath().normalize();
        String username = requireText(args[1], "github-user");
        String repositoryUrl = requireText(args[2], "repository-url");

        Files.createDirectories(settings.getParent());
        Document document = readOrCreate(settings);
        Element root = document.getDocumentElement();

        Element servers = child(document, root, "servers");
        Element server = identifiedChild(document, servers, "server", SERVER_ID);
        setText(document, server, "username", username);
        setText(document, server, "password", token);

        Element profiles = child(document, root, "profiles");
        Element profile = identifiedChild(document, profiles, "profile", PROFILE_ID);
        Element repositories = child(document, profile, "repositories");
        Element repository = identifiedChild(document, repositories, "repository", SERVER_ID);
        setText(document, repository, "name", "SubAuth GitHub Packages");
        setText(document, repository, "url", repositoryUrl);
        setText(document, child(document, repository, "releases"), "enabled", "true");
        setText(document, child(document, repository, "snapshots"), "enabled", "true");

        Element activeProfiles = child(document, root, "activeProfiles");
        ensureTextChild(document, activeProfiles, "activeProfile", PROFILE_ID);

        if (Files.exists(settings)) {
            Path backup = settings.resolveSibling(
                    settings.getFileName() + ".subauth-backup-" + BACKUP_TIME.format(Instant.now()));
            Files.copy(settings, backup, StandardCopyOption.COPY_ATTRIBUTES);
            restrictPermissions(backup);
            System.out.println("Backed up existing Maven settings to " + backup);
        }

        writeAtomically(document, settings);
        restrictPermissions(settings);
        System.out.println("Configured SubAuth GitHub Packages in " + settings);
    }

    private static Document readOrCreate(Path settings) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        if (Files.exists(settings)) {
            return factory.newDocumentBuilder().parse(settings.toFile());
        }

        Document document = factory.newDocumentBuilder().newDocument();
        Element root = document.createElement("settings");
        root.setAttribute("xmlns", SETTINGS_NAMESPACE);
        root.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
        root.setAttribute(
                "xsi:schemaLocation",
                SETTINGS_NAMESPACE + " https://maven.apache.org/xsd/settings-1.2.0.xsd");
        document.appendChild(root);
        return document;
    }

    private static Element child(Document document, Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        Element element = document.createElement(name);
        parent.appendChild(element);
        return element;
    }

    private static Element identifiedChild(
            Document document, Element parent, String name, String id) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) {
                Element idElement = directChild(element, "id");
                if (idElement != null && id.equals(idElement.getTextContent().trim())) {
                    return element;
                }
            }
        }
        Element element = document.createElement(name);
        parent.appendChild(element);
        setText(document, element, "id", id);
        return element;
    }

    private static void setText(Document document, Element parent, String name, String value) {
        Element element = directChild(parent, name);
        if (element == null) {
            element = document.createElement(name);
            parent.appendChild(element);
        }
        element.setTextContent(value);
    }

    private static void ensureTextChild(
            Document document, Element parent, String name, String value) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element
                    && element.getTagName().equals(name)
                    && value.equals(element.getTextContent().trim())) {
                return;
            }
        }
        Element element = document.createElement(name);
        element.setTextContent(value);
        parent.appendChild(element);
    }

    private static Element directChild(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static void writeAtomically(Document document, Path settings) throws Exception {
        Path temporary = Files.createTempFile(settings.getParent(), "subauth-settings-", ".xml");
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.transform(new DOMSource(document), new StreamResult(temporary.toFile()));
            try {
                Files.move(
                        temporary,
                        settings,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, settings, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrictPermissions(Path settings) throws IOException {
        try {
            Files.setPosixFilePermissions(
                    settings,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems retain their platform default permissions.
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
