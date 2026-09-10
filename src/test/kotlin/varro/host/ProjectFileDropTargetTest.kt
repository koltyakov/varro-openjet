package varro.host

import com.intellij.ide.dnd.FileFlavorProvider
import com.intellij.ide.dnd.DnDNativeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import varro.protocol.Json
import java.io.File
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

class ProjectFileDropTargetTest {
    @Test fun `project tree files and folders use the existing drop protocol`() {
        val drag = FileFlavorProvider {
            listOf(File("/project/Dockerfile"), File("/project/source files"), File("/project/Dockerfile"))
        }

        assertEquals(
            Json.message("files/drop", Json.obj("paths" to listOf("/project/Dockerfile", "/project/source files"))),
            ProjectFileDropTarget.message(drag),
        )
    }

    @Test fun `unrelated and empty drags do not create attachments`() {
        assertNull(ProjectFileDropTarget.message(Any()))
        assertNull(ProjectFileDropTarget.message(null))
        assertNull(ProjectFileDropTarget.message(FileFlavorProvider { emptyList() }))
        assertNull(ProjectFileDropTarget.message(FileFlavorProvider { null }))
    }

    @Test fun `native file drags accepted by the IDE target retain their paths`() {
        val transferable = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor): Any {
                if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
                return listOf(File("/project/Dockerfile"))
            }
        }
        val drag = DnDNativeTarget.EventInfo(transferable.transferDataFlavors, transferable)

        assertEquals(
            Json.message("files/drop", Json.obj("paths" to listOf("/project/Dockerfile"))),
            ProjectFileDropTarget.message(drag),
        )
    }
}
