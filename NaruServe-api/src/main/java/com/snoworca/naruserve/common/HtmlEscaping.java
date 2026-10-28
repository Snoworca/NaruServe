package com.snoworca.naruserve.common;

/** Shared text/attribute HTML escaping for generated error and directory-listing documents. */
// @req FR-APP-024 AC-3; FR-STATIC-003 AC-2
public final class HtmlEscaping {
    private HtmlEscaping() { }
    public static String escape(String text) {
        StringBuilder out=new StringBuilder(text.length());
        for(int i=0;i<text.length();i++) {
            switch(text.charAt(i)) {
                case '&':out.append("&amp;");break;
                case '<':out.append("&lt;");break;
                case '>':out.append("&gt;");break;
                case '"':out.append("&quot;");break;
                case '\'':out.append("&#39;");break;
                default:out.append(text.charAt(i));
            }
        }
        return out.toString();
    }
}
