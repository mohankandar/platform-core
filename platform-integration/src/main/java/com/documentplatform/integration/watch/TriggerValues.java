package com.documentplatform.integration.watch;

import com.documentplatform.integration.json.Json;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a trigger file and extracts named values by a fixed mapping: no scripts, no expressions.
 *
 * <ul>
 *   <li><b>JSON:</b> RFC 6901 pointers ({@code /loan/number}), with one addition: a segment {@code *} stands for every
 *       element of a list ({@code /files/*}{@code /name}). A pointer to a list of values gives each of them.</li>
 *   <li><b>XML:</b> a small path language: {@code /drop/loan/number} (element names, from the document element; any
 *       namespace prefix ignored), {@code [n]} to pick the n-th of several (from 1), and {@code @name} last for an
 *       attribute ({@code /drop/files/file/@name}). An element gives its text. A step without {@code [n]} gives every
 *       match.</li>
 * </ul>
 * Each value is a list (one entry for a single match, none when nothing matches). The values carry no meaning here:
 * the app decides what they are.
 *
 * <p><b>Hardened:</b> the file must be UTF-8 within the configured size; JSON is at most 32 levels deep; XML has no
 * DTD (a {@code DOCTYPE} is refused, so no entity can be declared: no "billion laughs"), no external entities or
 * schemas, no XInclude, and at most 64 levels. Anything else is {@link Unreadable}, without its text.
 */
public final class TriggerValues {

    /** The trigger cannot be read. Never holds the file's text. */
    public static final class Unreadable extends Exception {
        public Unreadable() {
            super("The trigger file cannot be read", null, false, false);
        }
    }

    public static final int MAX_XML_DEPTH = 64;
    public static final int MAX_VALUES = 1000;
    public static final int MAX_VALUE_LENGTH = 4000;

    private static final String NAME = "[A-Za-z_][A-Za-z0-9_.-]{0,99}";
    private static final Pattern XML_STEP = Pattern.compile("(" + NAME + ")(?:\\[([1-9][0-9]{0,5})])?");
    private static final Pattern XML_ATTR = Pattern.compile("@(" + NAME + ")");

    private TriggerValues() {
    }

    /** A JSON pointer (with {@code *} for every list element), at most 500 characters. */
    public static boolean validJsonPath(String p) {
        return p != null && p.length() <= 500 && p.matches("(/([^~/]|~[01])*)+");
    }

    /** An XML path as described above, at most 500 characters. */
    public static boolean validXmlPath(String p) {
        if (p == null || p.length() > 500 || !p.startsWith("/") || p.endsWith("/")) return false;
        String[] steps = p.substring(1).split("/", -1);
        for (int i = 0; i < steps.length; i++) {
            boolean last = i == steps.length - 1;
            if (XML_STEP.matcher(steps[i]).matches()) continue;
            if (last && i > 0 && XML_ATTR.matcher(steps[i]).matches()) continue;
            return false;
        }
        return true;
    }

    /** The text of a UTF-8 file; malformed UTF-8 is unreadable (a BOM is dropped). */
    static String utf8(byte[] bytes) throws Unreadable {
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            throw new Unreadable();
        }
    }

    /** A JSON trigger: one JSON value, at most 32 levels deep. */
    public static Object json(byte[] bytes) throws Unreadable {
        try {
            return Json.parse(utf8(bytes));
        } catch (Json.Invalid e) {
            throw new Unreadable();
        }
    }

    /** An XML trigger, parsed with every external access and every DTD refused. */
    public static Document xml(byte[] bytes) throws Unreadable {
        try {
            DocumentBuilder b = factory().newDocumentBuilder();
            b.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("no external entities");
            });
            b.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                }

                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            // Decoded here (UTF-8 only), so an encoding declaration cannot pick another one.
            Document d = b.parse(new InputSource(new StringReader(utf8(bytes))));
            if (depth(d.getDocumentElement(), 1) > MAX_XML_DEPTH) throw new Unreadable();
            return d;
        } catch (ParserConfigurationException | SAXException | IOException | RuntimeException e) {
            throw new Unreadable();
        }
    }

    private static DocumentBuilderFactory factory() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setValidating(false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try {
            f.setAttribute("jdk.xml.maxElementDepth", Integer.toString(MAX_XML_DEPTH));
        } catch (IllegalArgumentException notThisParser) {
            // the depth is also checked after parsing
        }
        return f;
    }

    private static int depth(Node n, int d) {
        int max = d;
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE) max = Math.max(max, depth(c, d + 1));
            if (max > MAX_XML_DEPTH) return max;
        }
        return max;
    }

    /** Every mapped value of a parsed trigger ({@link #json} or {@link #xml}); a missing one is an empty list. */
    public static Map<String, List<String>> extract(Object parsed, Map<String, String> mapping) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> m : mapping.entrySet()) {
            List<String> values = parsed instanceof Document d ? xmlValues(d, m.getValue())
                    : jsonValues(parsed, m.getValue());
            out.put(m.getKey(), List.copyOf(values));
        }
        return out;
    }

    // ---- JSON ----

    static List<String> jsonValues(Object root, String pointer) {
        List<Object> current = new ArrayList<>();
        current.add(root);
        for (String raw : pointer.substring(1).split("/", -1)) {
            String part = raw.replace("~1", "/").replace("~0", "~");
            List<Object> next = new ArrayList<>();
            for (Object cur : current) {
                if (cur instanceof Map<?, ?> map) {
                    if (map.containsKey(part)) next.add(map.get(part));
                } else if (cur instanceof List<?> list) {
                    if (part.equals("*")) {
                        next.addAll(list);
                    } else if (part.matches("0|[1-9]\\d{0,8}")) {
                        int n = Integer.parseInt(part);
                        if (n < list.size()) next.add(list.get(n));
                    }
                }
            }
            current = next;
        }
        List<String> out = new ArrayList<>();
        for (Object v : current) {
            if (v instanceof List<?> list) {
                for (Object x : list) add(out, x);
            } else {
                add(out, v);
            }
        }
        return out;
    }

    private static void add(List<String> out, Object v) {
        if (out.size() >= MAX_VALUES) return;
        if (v instanceof String || v instanceof Long || v instanceof Double || v instanceof Boolean) {
            String s = String.valueOf(v);
            if (s.length() <= MAX_VALUE_LENGTH) out.add(s);
        }
    }

    // ---- XML ----

    static List<String> xmlValues(Document d, String path) {
        String[] steps = path.substring(1).split("/", -1);
        List<Element> current = new ArrayList<>();
        Element root = d.getDocumentElement();
        Matcher first = XML_STEP.matcher(steps[0]);
        if (!first.matches() || !local(root).equals(first.group(1))
                || first.group(2) != null && !first.group(2).equals("1")) {
            return List.of();
        }
        current.add(root);
        List<String> out = new ArrayList<>();
        for (int i = 1; i < steps.length; i++) {
            Matcher attr = XML_ATTR.matcher(steps[i]);
            if (attr.matches()) {
                for (Element e : current) {
                    Attr a = attribute(e, attr.group(1));
                    if (a != null) addText(out, a.getValue());
                }
                return out;
            }
            Matcher m = XML_STEP.matcher(steps[i]);
            if (!m.matches()) return List.of();
            List<Element> next = new ArrayList<>();
            for (Element e : current) {
                List<Element> matches = new ArrayList<>();
                for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c instanceof Element ce && local(ce).equals(m.group(1))) matches.add(ce);
                }
                if (m.group(2) == null) {
                    next.addAll(matches);
                } else {
                    int n = Integer.parseInt(m.group(2));
                    if (n <= matches.size()) next.add(matches.get(n - 1));
                }
            }
            current = next;
        }
        for (Element e : current) addText(out, e.getTextContent());
        return out;
    }

    private static Attr attribute(Element e, String name) {
        org.w3c.dom.NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String local = a.getLocalName() != null ? a.getLocalName() : a.getName();
            if (local.equals(name)) return a;
        }
        return null;
    }

    private static String local(Element e) {
        String n = e.getLocalName();
        if (n != null) return n;
        String t = e.getTagName();
        return t.contains(":") ? t.substring(t.indexOf(':') + 1) : t;
    }

    private static void addText(List<String> out, String text) {
        if (out.size() >= MAX_VALUES || text == null) return;
        String s = text.strip();
        if (s.length() <= MAX_VALUE_LENGTH) out.add(s);
    }
}
