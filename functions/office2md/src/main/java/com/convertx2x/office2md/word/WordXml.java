package com.convertx2x.office2md.word;

import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class WordXml {
    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private WordXml() { }
    static boolean is(Node node, String name) {
        return node != null && W.equals(node.getNamespaceURI()) && name.equals(node.getLocalName());
    }
    static List<Node> children(Node node) {
        List<Node> result = new ArrayList<>();
        if (node != null) for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling())
            if (n.getNodeType() == Node.ELEMENT_NODE) result.add(n);
        return result;
    }
    static Node child(Node node, String name) {
        if (node != null) for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling())
            if (is(n, name)) return n;
        return null;
    }
    static String attr(Node node, String name) {
        return node instanceof Element e ? e.getAttributeNS(W, name) : "";
    }
    static String val(Node node) { return attr(node, "val"); }
    /** XMLBeans' DOM deliberately does not implement DOM Level 3 getTextContent(). */
    static String text(Node node) {
        StringBuilder result = new StringBuilder();
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling())
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE)
                result.append(n.getNodeValue());
        return result.toString();
    }
    static int integer(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return fallback; }
    }
    static boolean on(Node node) {
        return node != null && !List.of("false", "0", "off").contains(val(node).toLowerCase(java.util.Locale.ROOT));
    }
    static boolean omitted(Node node) { return is(node, "del") || is(node, "moveFrom"); }
}
