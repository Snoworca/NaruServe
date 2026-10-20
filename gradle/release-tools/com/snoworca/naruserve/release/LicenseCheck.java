package com.snoworca.naruserve.release;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** The license decision of releaseLicenses: which licenses a POM chain declares, and whether they may ship. */
public final class LicenseCheck {

    /** GPL, LGPL and AGPL by SPDX-style id or by their long names, plus non-open licenses that must not ship either. */
    private static final Pattern DENIED = Pattern.compile(
            "(?i)(\\b[AL]?GPL|General Public License|\\bSSPL|Server Side Public License|\\bBUSL|Business Source"
                    + "|Commons Clause|Proprietary|Commercial)");

    private LicenseCheck() {
    }

    /** One {@code <license>} of a POM: its name and URL, either possibly empty. */
    public static final class License {
        public final String name;
        public final String url;

        public License(String name, String url) {
            this.name = name;
            this.url = url;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Whether a dependency may ship, and why not. */
    public static final class Verdict {
        public final boolean pass;
        public final String message;

        Verdict(boolean pass, String message) {
            this.pass = pass;
            this.message = message;
        }
    }

    /**
     * The licenses of the first POM in {@code pomChain} (child first, then its parents) that declares any, since a
     * license declared only in a parent POM is the child's license; empty when no POM declares one.
     */
    // @req FR-REL-004
    public static List<License> licensesOf(List<String> pomChain) {
        for (String pom : pomChain) {
            List<License> licenses = new ArrayList<>();
            Element licensesElement = child(parse(pom), "licenses");
            if (licensesElement != null) {
                for (Node n = licensesElement.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element && "license".equals(localName(n))) {
                        licenses.add(new License(text((Element) n, "name"), text((Element) n, "url")));
                    }
                }
            }
            if (!licenses.isEmpty()) {
                return licenses;
            }
        }
        return new ArrayList<>();
    }

    /** Passes when at least one named license is not GPL-family, so a dual license with a permissive member ships. */
    // @req FR-REL-004
    public static Verdict verdict(List<License> licenses) {
        if (licenses.isEmpty()) {
            return new Verdict(false, "unknown license: no POM in the parent chain declares one");
        }
        for (License l : licenses) {
            if (!l.name.isEmpty() && !DENIED.matcher(l.name).find()) {
                return new Verdict(true, "ok: " + licenses);
            }
        }
        return new Verdict(false, "GPL-family, non-open or unnamed license only: " + licenses);
    }

    private static Element parse(String pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // POMs come from remote repositories: no DOCTYPE, so no external entity is ever fetched.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(pom.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        } catch (Exception e) {
            throw new IllegalArgumentException("unreadable POM: " + e.getMessage(), e);
        }
    }

    private static String localName(Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }

    /** The direct child element named {@code name}, so nested {@code <licenses>} of dependencies never count. */
    private static Element child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && name.equals(localName(n))) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? "" : e.getTextContent().trim().replaceAll("\\s+", " ");
    }
}
