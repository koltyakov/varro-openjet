package varro.host

import org.junit.Assert.*
import org.junit.Test
import varro.protocol.Json

class LinkContextMenuTest {
    @Test fun `copies HTTP and HTTPS targets without rewriting query strings`() {
        for (url in listOf("http://localhost:3000", "https://example.com/docs?q=one&two=2", "HTTP://example.com", "http://[::1]:3000")) {
            assertTrue(isAllowedExternalUrl(url))
            assertEquals(LinkContextMenu("Copy link", url), LinkContextMenu.from(
                Json.obj("webviewSection" to "varroExternalLink", "varroLinkUrl" to url)))
        }
    }

    @Test fun `copies full paths with spaces and Windows drive letters unchanged`() {
        for (path in listOf("/repo/src/shared/protocol.ts", "/repo/folder with spaces/file.ts", "C:/repo/src/App.tsx")) {
            assertEquals(LinkContextMenu("Copy path", path), LinkContextMenu.from(
                Json.obj("webviewSection" to "varroFileLink", "varroFilePath" to path)))
        }
    }

    @Test fun `rejects unsafe links and malformed copy contexts`() {
        for (url in listOf("javascript:alert(1)", "file:///repo/file.ts", "http://", "https:///path", "not a URL")) {
            assertFalse(isAllowedExternalUrl(url))
            assertNull(LinkContextMenu.from(Json.obj("webviewSection" to "varroExternalLink", "varroLinkUrl" to url)))
        }
        for (value in listOf(null, 42, "", " ")) {
            assertNull(LinkContextMenu.from(Json.obj("webviewSection" to "varroExternalLink", "varroLinkUrl" to value)))
            assertNull(LinkContextMenu.from(Json.obj("webviewSection" to "varroFileLink", "varroFilePath" to value)))
        }
        assertNull(LinkContextMenu.from(null))
        assertNull(LinkContextMenu.from(Json.obj()))
        assertNull(LinkContextMenu.from(Json.obj("webviewSection" to "other", "varroFilePath" to "/repo/file.ts")))
    }

    @Test fun `opens links the webview accepts that java URI would reject`() {
        assertEquals("https://a.com/x?q=a%7Cb", externalBrowserUrl("https://a.com/x?q=a|b"))
        assertEquals("https://a.com/a%20b/%7Bid%7D?x=%5B1%5D#%5E", externalBrowserUrl("https://a.com/a b/{id}?x=[1]#^"))
        assertEquals("https://a.com/100%25", externalBrowserUrl("https://a.com/100%"))
        assertEquals("https://a.com/ok%20", externalBrowserUrl("https://a.com/ok%20"))
        assertEquals("http://[::1]:3000/p", externalBrowserUrl("http://[::1]:3000/p"))
        assertTrue(isAllowedExternalUrl("https://a.com/x?q=a|b"))
        assertEquals(LinkContextMenu("Copy link", "https://a.com/x?q=a|b"), LinkContextMenu.from(
            Json.obj("webviewSection" to "varroExternalLink", "varroLinkUrl" to "https://a.com/x?q=a|b")))
        assertNull(externalBrowserUrl("https://a.com/\nInjected"))
        assertNull(externalBrowserUrl("https://a.com/" + "\u0000"))
    }
}
