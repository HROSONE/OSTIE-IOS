package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentPreviewTest {
    @Test fun svgAndXmlFormatsArePreviewable() {
        assertEquals("html", DocumentPreview.detectFormat("svg", "<svg></svg>"))
        assertEquals("html", DocumentPreview.detectFormat("HTML5", "<p>oi</p>"))
        assertEquals("html", DocumentPreview.detectFormat("xml", "<svg/>"))
    }

    @Test fun markupIsDetectedEvenWhenModelSaysText() {
        assertEquals("html", DocumentPreview.detectFormat("text", "<!DOCTYPE html><html></html>"))
        assertEquals("html", DocumentPreview.detectFormat(null, "  <svg viewBox=\"0 0 10 10\"></svg>"))
        assertEquals("text", DocumentPreview.detectFormat("text", "Um poema sobre o mar"))
        assertEquals("text", DocumentPreview.detectFormat("text", "fun main() { println(\"<p>\") }"))
    }

    @Test fun surroundingMarkdownFenceIsRemoved() {
        assertEquals("<p>oi</p>", DocumentPreview.stripFence("```html\n<p>oi</p>\n```"))
        assertEquals("<svg></svg>", DocumentPreview.stripFence("  ```svg\n<svg></svg>```  "))
        assertEquals("sem cerca", DocumentPreview.stripFence("sem cerca"))
    }

    @Test fun svgIsWrappedInPageThatFitsScreen() {
        val page = DocumentPreview.page("<?xml version=\"1.0\"?>\n<svg width=\"900\"><circle r=\"4\"/></svg>")
        assertTrue(page.startsWith("<!doctype html>"))
        assertTrue(page.contains("<body><svg width=\"900\">"))
        assertFalse(page.contains("<?xml"))
    }

    @Test fun fragmentsGetDocumentAndFullPagesKeepTheirMarkup() {
        assertTrue(DocumentPreview.page("<h1>Oi</h1>").contains("<body><h1>Oi</h1></body>"))
        val full = "<!doctype html><html><head><title>x</title></head><body>a</body></html>"
        assertTrue(DocumentPreview.page(full).contains("<head><meta name=\"viewport\""))
    }

    @Test fun largestChatCodeBlockIsExtracted() {
        val message = "Veja:\n```css\na{}\n```\ne também\n```html\n<div>grande bloco</div>\n```\nPronto."
        assertEquals("html" to "<div>grande bloco</div>", DocumentPreview.codeBlock(message))
        assertNull(DocumentPreview.codeBlock("Sem código aqui."))
    }

    @Test fun markupIsFoundInsideExplanations() {
        val answer = "Claro! Aqui está sua página:\n\n```html\n<!doctype html><html><body><h1>Oi</h1></body></html>\n```\n\nQuer mudar algo?"
        assertTrue(DocumentPreview.looksLikeMarkup(answer))
        assertEquals("html", DocumentPreview.detectFormat("text", answer))
        assertEquals("<!doctype html><html><body><h1>Oi</h1></body></html>", DocumentPreview.extractMarkup(answer))
        val svg = "Segue o desenho: <svg viewBox=\"0 0 10 10\"><circle r=\"4\"/></svg> pronto."
        assertEquals("<svg viewBox=\"0 0 10 10\"><circle r=\"4\"/></svg>", DocumentPreview.extractMarkup(svg))
        assertTrue(DocumentPreview.page(svg).contains("<body><svg viewBox"))
    }

    @Test fun networkResourcesAreDetected() {
        assertTrue(DocumentPreview.usesNetwork("<script src=\"https://cdn.tailwindcss.com\"></script>"))
        assertTrue(DocumentPreview.usesNetwork("<link href='https://fonts.googleapis.com/css2?family=Inter' rel=stylesheet>"))
        assertFalse(DocumentPreview.usesNetwork("<p>Sem nada externo</p><a href=\"#topo\">topo</a>"))
    }
}
