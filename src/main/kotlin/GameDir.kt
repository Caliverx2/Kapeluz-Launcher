import java.io.File

val GameDir: File by lazy {
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