package org.lewapnoob.KapeLuzFileServer

import com.sun.net.httpserver.HttpServer
import java.awt.*
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

private val launcherGameDir: File by lazy {
    val appName = "KapeLuz"
    val dottedName = ".$appName"

    val userHome = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase(java.util.Locale.ROOT)

    val path = when {
        os.contains("win") -> {
            val appData = System.getenv("APPDATA")
            if (appData != null) File(appData, dottedName) else File(userHome, dottedName)
        }
        os.contains("mac") -> {
            File(userHome, "Library/Application Support/$dottedName")
        } else -> {
            File(userHome, dottedName)
        }
    }
    path.apply { mkdirs() }
}

class KapeLuzFileServer : JFrame("KapeLuz - File Server") {

    private var server: HttpServer? = null
    private val port = 4777
    
    // Stan serwera
    private var activeStreamFolder: File? = null
    // Zbiór plików wykluczonych (nazwy plików)
    private val excludedFiles = ConcurrentHashMap.newKeySet<String>()
    private var isServerRunning = false

    // Aktualnie przeglądany folder (niekoniecznie streamowany)
    private var currentBrowsingFolder: File? = null

    // UI Components
    private val pathField = JTextField()
    private val goButton = JButton("Go")
    
    // Tabela plików (Prawa strona)
    // Kolumny: [Stream? (Boolean), File Name (String), Size (String)]
    private val fileTableModel = object : DefaultTableModel(arrayOf("Stream?", "File Name", "Size"), 0) {
        override fun getColumnClass(columnIndex: Int): Class<*> {
            return if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java
        }
        override fun isCellEditable(row: Int, column: Int) = column == 0 // Tylko checkbox edytowalny
    }
    private val fileTable = JTable(fileTableModel)
    
    // Drzewo katalogów (Lewa strona)
    private val rootNode = DefaultMutableTreeNode("Computer")
    private val treeModel = DefaultTreeModel(rootNode)
    private val dirTree = JTree(treeModel)

    // Panel sterowania streamowaniem
    private val streamToggle = JToggleButton("STREAMING OFF")
    private val statusLabel = JLabel("Status: Idle")
    private val urlPreviewField = JTextField("Select a file to see URL").apply { 
        isEditable = false 
        background = Color(230, 230, 230)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.GRAY), 
            EmptyBorder(5, 5, 5, 5)
        )
    }

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(900, 600)
        setLocationRelativeTo(null)

        setupUI()
        initTree()
        
        // Domyślnie otwórz folder gry
        val defaultDir = File(launcherGameDir, "versions").apply { mkdirs() }
        loadFolderToTable(defaultDir)
        pathField.text = defaultDir.absolutePath
    }

    private fun setupUI() {
        layout = BorderLayout()

        // --- TOP: Path Bar ---
        val topPanel = JPanel(BorderLayout(5, 5))
        topPanel.border = EmptyBorder(5, 5, 5, 5)
        topPanel.add(JLabel("Path:"), BorderLayout.WEST)
        topPanel.add(pathField, BorderLayout.CENTER)
        topPanel.add(goButton, BorderLayout.EAST)
        
        pathField.addActionListener { loadPathFromField() }
        goButton.addActionListener { loadPathFromField() }
        
        add(topPanel, BorderLayout.NORTH)

        // --- CENTER: Split Pane (Tree | Files) ---
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.dividerLocation = 250

        // LEFT: Directory Tree
        val treeScroll = JScrollPane(dirTree)
        splitPane.leftComponent = treeScroll

        // RIGHT: File List & Controls
        val rightPanel = JPanel(BorderLayout())
        
        // Right Top: Streaming Controls
        val streamPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        streamPanel.border = BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY)
        
        streamToggle.font = Font("Arial", Font.BOLD, 14)
        streamToggle.addActionListener { toggleStreaming() }
        
        streamPanel.add(streamToggle)
        streamPanel.add(Box.createHorizontalStrut(20))
        streamPanel.add(statusLabel)
        
        rightPanel.add(streamPanel, BorderLayout.NORTH)

        // Right Center: Table
        fileTable.rowHeight = 25
        fileTable.columnModel.getColumn(0).maxWidth = 60 // Checkbox column width
        fileTable.columnModel.getColumn(2).maxWidth = 100 // Size column width
        
        // Listener do aktualizacji wykluczeń w czasie rzeczywistym
        fileTable.model.addTableModelListener { e ->
            if (e.column == 0 && isServerRunning && activeStreamFolder == currentBrowsingFolder) {
                updateExclusionsFromTable()
            }
        }
        
        // Listener do podglądu URL
        fileTable.selectionModel.addListSelectionListener {
            val row = fileTable.selectedRow
            if (row != -1) {
                val fileName = fileTable.getValueAt(row, 1) as String
                updateUrlPreview(fileName)
            }
        }

        rightPanel.add(JScrollPane(fileTable), BorderLayout.CENTER)

        // Right Bottom: URL Preview
        val previewPanel = JPanel(BorderLayout())
        previewPanel.border = EmptyBorder(5, 5, 5, 5)
        previewPanel.add(JLabel("Web Browser URL Preview: "), BorderLayout.WEST)
        previewPanel.add(urlPreviewField, BorderLayout.CENTER)
        rightPanel.add(previewPanel, BorderLayout.SOUTH)

        splitPane.rightComponent = rightPanel
        add(splitPane, BorderLayout.CENTER)
    }

    private fun initTree() {
        rootNode.removeAllChildren()
        
        val os = System.getProperty("os.name").lowercase(java.util.Locale.ROOT)
        if (os.contains("win")) {
            // Dodaj dyski systemowe (Windows)
            File.listRoots().forEach { root ->
                val driveNode = DefaultMutableTreeNode(root)
                driveNode.add(DefaultMutableTreeNode("Loading..."))
                rootNode.add(driveNode)
            }
        } else {
            // Linux/Mac: Tylko katalog domowy
            val userHome = File(System.getProperty("user.home"))
            val homeNode = DefaultMutableTreeNode(userHome)
            homeNode.add(DefaultMutableTreeNode("Loading..."))
            rootNode.add(homeNode)
        }
        treeModel.reload()

        // Lazy loading (ładowanie podfolderów przy rozwijaniu)
        dirTree.addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as DefaultMutableTreeNode
                if (node.childCount == 1 && node.getFirstChild().toString() == "Loading...") {
                    node.removeAllChildren()
                    val file = if (node.userObject is File) node.userObject as File else File(node.userObject.toString())
                    
                    val subDirs = file.listFiles { f -> f.isDirectory && !f.isHidden }
                    subDirs?.sorted()?.forEach { dir ->
                        val childNode = DefaultMutableTreeNode(dir)
                        childNode.add(DefaultMutableTreeNode("Loading...")) // Dummy dla dzieci
                        node.add(childNode)
                    }
                    treeModel.nodeStructureChanged(node)
                }
            }
            override fun treeWillCollapse(event: TreeExpansionEvent) {}
        })

        // Custom Renderer (żeby pokazywać tylko nazwę folderu, a nie pełną ścieżkę)
        dirTree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(tree: JTree?, value: Any?, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean): Component {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                val node = value as DefaultMutableTreeNode
                if (node.userObject is File) {
                    val f = node.userObject as File
                    text = if (f.absolutePath == f.path && f.parent == null) f.absolutePath else f.name
                    if (text.isEmpty()) text = f.absolutePath // Fallback dla dysków
                }
                return this
            }
        }

        // Obsługa kliknięcia w drzewo
        dirTree.addTreeSelectionListener {
            val node = dirTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            if (node.userObject is File) {
                loadFolderToTable(node.userObject as File)
            }
        }
    }

    private fun loadPathFromField() {
        val f = File(pathField.text)
        if (!f.exists() || !f.isDirectory) {
            JOptionPane.showMessageDialog(this, "Invalid directory path!")
            return
        }

        // Zabezpieczenie: Na Linux/Mac nie wychodź poza home
        val os = System.getProperty("os.name").lowercase(java.util.Locale.ROOT)
        if (!os.contains("win")) {
            val userHome = File(System.getProperty("user.home")).canonicalFile
            val target = f.canonicalFile
            if (target != userHome && !target.path.startsWith(userHome.path + File.separator)) {
                JOptionPane.showMessageDialog(this, "Access restricted to User Home directory.")
                pathField.text = currentBrowsingFolder?.absolutePath ?: userHome.absolutePath
                return
            }
        }

        loadFolderToTable(f)
    }

    private fun loadFolderToTable(folder: File) {
        currentBrowsingFolder = folder
        pathField.text = folder.absolutePath
        
        // Wyczyść tabelę
        fileTableModel.rowCount = 0
        
        val files = folder.listFiles { f -> f.isFile && !f.isHidden } ?: return
        
        files.forEach { file ->
            // Domyślnie zaznaczone, chyba że jest na liście wykluczonych (i to ten sam folder co aktywny)
            val isExcluded = if (activeStreamFolder == folder) excludedFiles.contains(file.name) else false
            val sizeKB = file.length() / 1024
            fileTableModel.addRow(arrayOf(!isExcluded, file.name, "${sizeKB} KB"))
        }

        // Aktualizuj stan przycisku Stream
        if (activeStreamFolder == folder && isServerRunning) {
            streamToggle.isSelected = true
            streamToggle.text = "STREAMING ON"
            streamToggle.background = Color.GREEN
            statusLabel.text = "Status: Serving this folder"
            statusLabel.foreground = Color(0, 150, 0)
        } else {
            streamToggle.isSelected = false
            streamToggle.text = "STREAMING OFF"
            streamToggle.background = null
            if (isServerRunning) {
                statusLabel.text = "Status: Serving OTHER folder (${activeStreamFolder?.name})"
                statusLabel.foreground = Color.ORANGE
            } else {
                statusLabel.text = "Status: Idle"
                statusLabel.foreground = Color.BLACK
            }
        }
    }

    private fun toggleStreaming() {
        if (streamToggle.isSelected) {
            // Start Streaming
            val folder = currentBrowsingFolder ?: return
            
            // Jeśli serwer już działał na innym folderze, zatrzymaj go logicznie
            stopHttpServer() 
            
            activeStreamFolder = folder
            updateExclusionsFromTable()
            startHttpServer()
            
            streamToggle.text = "STREAMING ON"
            streamToggle.background = Color.GREEN
            statusLabel.text = "Status: Serving ${folder.name}"
            statusLabel.foreground = Color(0, 150, 0)
        } else {
            // Stop Streaming
            stopHttpServer()
            activeStreamFolder = null
            
            streamToggle.text = "STREAMING OFF"
            streamToggle.background = null
            statusLabel.text = "Status: Idle"
            statusLabel.foreground = Color.BLACK
        }
    }

    private fun updateExclusionsFromTable() {
        excludedFiles.clear()
        for (i in 0 until fileTableModel.rowCount) {
            val isChecked = fileTableModel.getValueAt(i, 0) as Boolean
            val fileName = fileTableModel.getValueAt(i, 1) as String
            if (!isChecked) {
                excludedFiles.add(fileName)
            }
        }
    }

    private fun updateUrlPreview(fileName: String) {
        try {
            val ip = InetAddress.getLocalHost().hostAddress
            urlPreviewField.text = "http://$ip:$port/$fileName"
        } catch (e: Exception) {
            urlPreviewField.text = "http://localhost:$port/$fileName"
        }
    }

    private fun startHttpServer() {
        if (isServerRunning) return // Już działa
        
        try {
            server = HttpServer.create(InetSocketAddress(port), 0)

            server?.createContext("/") { exchange ->
                val requestedFileName = exchange.requestURI.path.substringAfterLast("/")
                
                // Sprawdź czy mamy aktywny folder
                val folder = activeStreamFolder
                
                if (folder != null && !excludedFiles.contains(requestedFileName)) {
                    val fileToStream = File(folder, requestedFileName)
                    
                    // Zabezpieczenie: Plik musi istnieć i być wewnątrz wybranego folderu (bez wychodzenia w górę)
                    if (fileToStream.exists() && fileToStream.isFile && fileToStream.parentFile == folder) {
                        
                        exchange.responseHeaders.set("Content-Type", "application/java-archive")
                        exchange.sendResponseHeaders(200, fileToStream.length())

                        fileToStream.inputStream().use { input ->
                            exchange.responseBody.use { output ->
                                input.copyTo(output)
                            }
                        }
                        println("SUCCESS: Streamed $requestedFileName to ${exchange.remoteAddress}")
                    } else {
                        val response = "404 - File not found or excluded".toByteArray()
                        exchange.sendResponseHeaders(404, response.size.toLong())
                        exchange.responseBody.write(response)
                        exchange.responseBody.close()
                        println("404: Access denied or missing: $requestedFileName")
                    }
                } else {
                    val response = "403 - Access Forbidden (Excluded or No Folder Active)".toByteArray()
                    exchange.sendResponseHeaders(404, response.size.toLong())
                    exchange.responseBody.write(response)
                    exchange.responseBody.close()
                }
            }

            server?.executor = null
            server?.start()
            isServerRunning = true
            println("Server started on port $port")

        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Failed to start server: ${e.message}")
            isServerRunning = false
        }
    }

    private fun stopHttpServer() {
        server?.stop(0)
        server = null
        isServerRunning = false
        println("Server stopped")
    }
}

fun main() {
    // Ustawienie wyglądu systemowego dla Swinga
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())

    SwingUtilities.invokeLater {
        KapeLuzFileServer().isVisible = true
    }
}