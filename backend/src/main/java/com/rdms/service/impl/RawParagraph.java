package com.rdms.service.impl;

class RawParagraph {
    private final String style;
    private final String text;
    private final int levelHint;
    private final String html;

    RawParagraph(String style, String text, int levelHint, String html) {
        this.style = style;
        this.text = text;
        this.levelHint = levelHint;
        this.html = html;
    }

    String getStyle() {
        return style;
    }

    String getText() {
        return text;
    }

    int getLevelHint() {
        return levelHint;
    }

    String getHtml() {
        return html;
    }
}
