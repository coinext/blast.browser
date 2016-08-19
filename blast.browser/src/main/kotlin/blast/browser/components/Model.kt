package blast.browser.components

import blast.browser.utils.getValue
import blast.browser.utils.setValue
import blast.browser.utils.xmlSafeUUID
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.ui.treeStructure.SimpleNode
import com.intellij.ui.treeStructure.SimpleTreeStructure
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.containers.ContainerUtil
import org.jdom.Element
import javax.swing.tree.TreePath
import kotlin.comparisons.compareValuesBy

object BlastBrowser {
    object DataKeys {
        val TARGET_TREE = DataKey.create<Tree>("targetBookmarkTree")
        val BROWSER_EDITOR = DataKey.create<BaseBrowserEditor>("sourceBrowserEditor")
    }
}

interface IDNode : Comparable<BookmarkNode> {
    val displayName: String
    val id: String get
    override fun compareTo(other: BookmarkNode): Int = compareValuesBy(this, other, { it.id })
    fun type(): String
    fun treePath(): TreePath
}

abstract class BookmarkNode(internal var element: Element) : SimpleNode(), IDNode {
    override val id: String
        get() {
            return element.name
        }

    override var displayName: String by element

    init {
        element.setAttribute("type", this.type())
    }

    override fun getName(): String = displayName

    override fun update(presentation: PresentationData) {
        super.update(presentation)
        presentation.presentableText = displayName
    }

    override fun treePath(): TreePath {
        val path = mutableListOf<BookmarkNode>(this)

        var currentNode: BookmarkNode = this

        while(currentNode.parent != null) {
            val parent = currentNode.parent as BookmarkNode
            path.add(parent)
            currentNode = parent
        }
        return TreePath(path.asReversed().toTypedArray())
    }
}

class BookmarkDirectory(element: Element) : BookmarkNode(element) {
    constructor(name: String, id: String) : this(Element(id)) {
        displayName = name
        element.name = id
    }

    override fun type(): String = "directory"
    override fun isAlwaysLeaf(): Boolean = false

    override fun getChildren(): Array<out BookmarkNode> = element.getChildren().map {
        val type: String = it.getAttribute("type").value
        when (type) {
            "directory" -> BookmarkDirectory(it)
            "bookmark" -> Bookmark(it)
            else -> throw Exception("$type not recognised")
        }
    }.toTypedArray()

    override fun update(presentation: PresentationData) {
        super.update(presentation)
        presentation.setIcon(AllIcons.Nodes.Folder)
    }

    internal fun addNode(node: BookmarkNode) {
        element.addContent(node.element)
    }

    internal fun removeNode(node: BookmarkNode) {
        element.removeChild(node.id)
    }
}

class Bookmark(element: Element) : BookmarkNode(element) {
    var url: String by element

    constructor(name: String, url: String) : this(Element(xmlSafeUUID())) {
        displayName = name
        this.url = url
    }

    override fun update(presentation: PresentationData) {
        super.update(presentation)
        presentation.locationString = url
        presentation.setIcon(AllIcons.General.Web!!)
    }

    override fun type() = "bookmark"
    override fun isAlwaysLeaf(): Boolean = true
    override fun getChildren(): Array<out SimpleNode> = emptyArray()
}

interface BookmarkManager {
    companion object {
        fun instance(project: Project) = ServiceManager.getService(BookmarkManager::class.java)
    }

    fun addBookmarkListener(listener: BookmarkListener)
    fun removeBookmarkListener(listener: BookmarkListener)

    fun addNode(parent: BookmarkDirectory, node: BookmarkNode)
    fun removeNode(parent: BookmarkDirectory, node: BookmarkNode)
    fun updateNode(node: BookmarkNode)
}

interface BookmarkListener {
    fun rootsChanged()

    fun itemUpdated(node: BookmarkNode)

    fun itemAdded(parent: BookmarkDirectory, node: BookmarkNode)

    fun itemRemoved(parent: BookmarkDirectory, node: BookmarkNode)

    fun parentChanged(parent: BookmarkDirectory)
}

@State(name = "bookmarks", storages = arrayOf(Storage("bookmark.xml")))
class BookmarkManagerImpl(
        val project: Project
) : SimpleTreeStructure(), PersistentStateComponent<Element>, BookmarkManager {
    val root = BookmarkDirectory("root", "root")
    private val myListeners: MutableList<BookmarkListener> = ContainerUtil.createLockFreeCopyOnWriteList()

    override fun getState(): Element = root.element.clone()
    override fun loadState(state: Element) { root.element.addContent(state.cloneContent()) }
    override fun getRootElement(): Any = root

    override fun addBookmarkListener(listener: BookmarkListener) {
        // FIXTURES
        if (root.element.contentSize == 0) {
            val f = BookmarkDirectory("Programming", "programming")
            f.addNode(Bookmark("Slashdot", "http://www.slashdot.com"))
            f.addNode(Bookmark("Macrumors", "http://www.macrumors.com"))
            f.addNode(Bookmark("Stackoverflow", "http://www.stackoverflow.com"))
            f.addNode(Bookmark("Google", "http://www.google.com"))
            f.addNode(Bookmark("Basecamp", "http://www.basecamp.com"))
            root.addNode(f)
        }
        myListeners.add(listener)
        listener.rootsChanged()
    }

    override fun removeBookmarkListener(listener: BookmarkListener) { myListeners.remove(listener) }

    override fun addNode(parent: BookmarkDirectory, node: BookmarkNode) {
        parent.addNode(node)
        myListeners.forEach { it.parentChanged(parent); it.itemAdded(parent, node) }
    }

    override fun removeNode(parent: BookmarkDirectory, node: BookmarkNode) {
        parent.removeNode(node)
        myListeners.forEach { it.parentChanged(parent); it.itemRemoved(parent, node) }
    }

    override fun updateNode(node: BookmarkNode) {
        myListeners.forEach { it.itemUpdated(node) }
    }
}