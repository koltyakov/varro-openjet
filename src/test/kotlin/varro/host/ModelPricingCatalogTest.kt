package varro.host

import org.junit.Assert.*
import org.junit.Test

class ModelPricingCatalogTest {
    private val source = """{"OpenAI":{"models":{
        "paid":{"cost":{"input":2,"output":8,"cache_read":0,"cache_write":-1}},
        "free":{"cost":{"input":0,"output":0}},
        "invalid":{"cost":{"input":"2","output":null}},
        "missing":{}
    }},"invalid":[]} """

    @Test fun `returns valid paid rates with case insensitive providers and preserves zero rates`() {
        val catalog = ModelPricingCatalog(load = { source })
        val pricing = catalog.get("OPENAI", "paid")!!
        assertEquals(2.0, pricing.get("input").asDouble, 0.0)
        assertEquals(0.0, pricing.get("cache_read").asDouble, 0.0)
        assertFalse(pricing.has("cache_write"))
        for (model in listOf("free", "invalid", "missing", "unknown", "PAID")) {
            assertNull(catalog.get("openai", model))
        }
        pricing.addProperty("input", 99)
        assertEquals(2.0, catalog.get("openai", "paid")!!.get("input").asDouble, 0.0)
    }

    @Test fun `caches the catalog including misses for an hour`() {
        var time = 0L
        var loads = 0
        val catalog = ModelPricingCatalog(load = { loads++; source }, now = { time })
        catalog.get("openai", "paid")
        time = 3_599_999
        catalog.get("openai", "unknown")
        assertEquals(1, loads)
        time = 3_600_000
        catalog.get("openai", "paid")
        assertEquals(2, loads)
    }

    @Test fun `failed loads can be retried`() {
        var loads = 0
        val catalog = ModelPricingCatalog(load = { if (++loads == 1) "[]" else source })
        assertThrows(IllegalStateException::class.java) { catalog.get("openai", "paid") }
        assertNotNull(catalog.get("openai", "paid"))
        assertEquals(2, loads)
    }
}
