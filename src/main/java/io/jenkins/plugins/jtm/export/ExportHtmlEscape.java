package io.jenkins.plugins.jtm.export;

final class ExportHtmlEscape {
    private ExportHtmlEscape() {}

    static String text(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':
                    b.append("&amp;");
                    break;
                case '<':
                    b.append("&lt;");
                    break;
                case '>':
                    b.append("&gt;");
                    break;
                case '"':
                    b.append("&quot;");
                    break;
                case '\'':
                    b.append("&#39;");
                    break;
                default:
                    b.append(c);
            }
        }
        return b.toString();
    }
}
