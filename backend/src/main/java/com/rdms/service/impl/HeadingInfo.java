package com.rdms.service.impl;

class HeadingInfo {
    private final String catalogNo;
    private final String title;
    private final int level;

    HeadingInfo(String catalogNo, String title, int level) {
        this.catalogNo = catalogNo;
        this.title = title;
        this.level = level;
    }

    String getCatalogNo() {
        return catalogNo;
    }

    String getTitle() {
        return title;
    }

    int getLevel() {
        return level;
    }
}
