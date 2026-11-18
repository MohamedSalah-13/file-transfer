package com.hamza.filetransfer.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SearchTextTest {

    private static boolean finds(String text, String query) {
        return SearchText.contains(text, SearchText.normalize(query));
    }

    @Test
    void caseAndAccentsDoNotMatter() {
        assertTrue(finds("Quarterly REPORT.pdf", "report"));
        assertTrue(finds("Meet at the café", "cafe"));
        assertTrue(finds("Meet at the cafe", "CAFÉ"));
    }

    @Test
    void arabicLettersPeopleTypeInterchangeablyMatch() {
        assertTrue(finds("أحمد أرسل الملف", "احمد"), "hamza on alef");
        assertTrue(finds("إلى مكتبة الجامعة", "الي مكتبه"), "alef maksura and ta marbuta");
        assertTrue(finds("مسؤول", "مسوول"), "waw with hamza");
        assertTrue(finds("القرآن", "القران"), "alef madda");
    }

    @Test
    void arabicDiacriticsAndTatweelAreIgnored() {
        assertTrue(finds("مُحَمَّد", "محمد"));
        assertTrue(finds("محمد", "مُحَمَّد"));
        assertTrue(finds("كـتـاب جديد", "كتاب"));
    }

    @Test
    void emptyQueriesFindNothing() {
        assertFalse(finds("anything", ""));
        assertFalse(finds("anything", "   "));
        assertFalse(finds(null, "x"));
        assertFalse(finds("report", "invoice"));
    }
}
