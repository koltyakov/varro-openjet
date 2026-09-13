package varro.host

import com.intellij.ide.dnd.DnDNativeTarget
import org.junit.Assert.*
import org.junit.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

class IdeDropObjectsTest {
    private val local = DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=java.lang.Object")

    private fun transfer(value: Any) = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.stringFlavor, local)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == local || flavor == DataFlavor.stringFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            check(flavor == local) { "Must not read Database Explorer's text export" }
            return value
        }
    }

    @Test fun `unwraps Database Explorer local object arrays and native wrappers`() {
        val first = Any()
        val second = Any()
        val drag = transfer(arrayOf(first, second))
        assertEquals(listOf(first, second), IdeDropObjects.objects(drag))
        assertEquals(listOf(first, second), IdeDropObjects.objects(DnDNativeTarget.EventInfo(drag.transferDataFlavors, drag)))
    }

    @Test fun `does not guess tables from text drops`() {
        assertTrue(IdeDropObjects.objects(StringSelection("main.users")).isEmpty())
        assertTrue(IdeDropObjects.objects(null).isEmpty())
    }

    @Test fun `bounds recursive and oversized transfers`() {
        val recursive = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(local)
            override fun isDataFlavorSupported(flavor: DataFlavor) = true
            override fun getTransferData(flavor: DataFlavor) = this
        }
        assertTrue(IdeDropObjects.objects(recursive).isEmpty())
        assertEquals(100, IdeDropObjects.objects(Array(1000) { Any() }).size)
    }
}
