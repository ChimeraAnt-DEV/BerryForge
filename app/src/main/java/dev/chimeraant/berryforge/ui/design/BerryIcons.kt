package dev.chimeraant.berryforge.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * BerryForge icon set.
 *
 * Drawn for this app on a 24x24 grid with a 1.75 stroke, open terminals and a squarer
 * silhouette than the stock Material set. No Material icon is referenced anywhere in
 * the project — [Icon] in ui/components renders these.
 */
object BerryIcons {

    private fun stroke(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.75f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun solid(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { d ->
                addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
            }
        }.build()


    // ---- Navigation ----
    val ChevronRight = stroke("chevron-right", "M9.5 5.5 L16 12 L9.5 18.5")
    val ChevronLeft = stroke("chevron-left", "M14.5 5.5 L8 12 L14.5 18.5")
    val ChevronDown = stroke("chevron-down", "M5.5 9.5 L12 16 L18.5 9.5")
    val ChevronUp = stroke("chevron-up", "M5.5 14.5 L12 8 L18.5 14.5")
    val ArrowRight = stroke("arrow-right", "M4 12 H19", "M13.5 6.5 L19 12 L13.5 17.5")
    val ArrowLeft = stroke("arrow-left", "M20 12 H5", "M10.5 6.5 L5 12 L10.5 17.5")
    val ArrowUp = stroke("arrow-up", "M12 19 V5", "M6.5 10.5 L12 5 L17.5 10.5")
    val ArrowDown = stroke("arrow-down", "M12 5 V19", "M6.5 13.5 L12 19 L17.5 13.5")
    val Menu = stroke("menu", "M4 7 H20", "M4 12 H20", "M4 17 H14")
    val Close = stroke("close", "M6 6 L18 18", "M18 6 L6 18")
    val More = stroke("more-vertical", "M12 5.5 m-1.4 0 a1.4 1.4 0 1 0 2.8 0 a1.4 1.4 0 1 0 -2.8 0", "M12 12 m-1.4 0 a1.4 1.4 0 1 0 2.8 0 a1.4 1.4 0 1 0 -2.8 0", "M12 18.5 m-1.4 0 a1.4 1.4 0 1 0 2.8 0 a1.4 1.4 0 1 0 -2.8 0")
    val MoreHorizontal = stroke("more-horizontal", "M5.5 12 m-1.4 0 a1.4 1.4 0 1 0 2.8 0 a1.4 1.4 0 1 0 -2.8 0", "M12 12 m-1.4 0 a1.4 1.4 0 1 0 2.8 0 a1.4 1.4 0 1 0 -2.8 0", "M18.5 12 m-1.4 0 a1.4 1.4 0 1 0 2.8 0 a1.4 1.4 0 1 0 -2.8 0")

    // ---- Git ----
    val Branch = stroke(
        "branch",
        "M7 4.5 V15",
        "M7 4.5 m-2.2 0 a2.2 2.2 0 1 0 4.4 0 a2.2 2.2 0 1 0 -4.4 0",
        "M7 19.5 m-2.2 0 a2.2 2.2 0 1 0 4.4 0 a2.2 2.2 0 1 0 -4.4 0",
        "M17.5 8.5 m-2.2 0 a2.2 2.2 0 1 0 4.4 0 a2.2 2.2 0 1 0 -4.4 0",
        "M17.5 10.7 C17.5 15.4 13.5 15 9.2 15.6",
    )
    val Commit = stroke(
        "commit",
        "M3 12 H8",
        "M16 12 H21",
        "M12 12 m-4 0 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0",
    )
    val PullRequest = stroke(
        "pull-request",
        "M6.5 9 V20",
        "M6.5 4.5 m-2.2 0 a2.2 2.2 0 1 0 4.4 0 a2.2 2.2 0 1 0 -4.4 0",
        "M6.5 19.5 m-2.2 0 a2.2 2.2 0 1 0 4.4 0 a2.2 2.2 0 1 0 -4.4 0",
        "M17.5 4.5 m-2.2 0 a2.2 2.2 0 1 0 4.4 0 a2.2 2.2 0 1 0 -4.4 0",
        "M17.5 6.7 V13",
        "M14.5 10 L17.5 13.5 L20.5 10",
    )
    val Merge = stroke(
        "merge",
        "M6.5 6.5 m-2.2 0 a2.2 2.2 0 1 0 4.4 0 a2.2 2.2 0 1 0 -4.4 0",
        "M6.5 19.5 m-2.2 0 a2.2 2.2 0 1 0 4.4 0 a2.2 2.2 0 1 0 -4.4 0",
        "M17.5 12.5 m-2.2 0 a2.2 2.2 0 1 0 4.4 0 a2.2 2.2 0 1 0 -4.4 0",
        "M6.5 8.7 V17.3",
        "M8.7 12.5 H15.3",
    )
    val Diff = stroke("diff", "M6 3.5 V20.5", "M12 8 H19", "M12 16 H19", "M9.5 8 H14", "M9.5 16 H14")

    // ---- Files ----
    val File = stroke("file", "M13.5 3.5 H7 A1.5 1.5 0 0 0 5.5 5 V19 A1.5 1.5 0 0 0 7 20.5 H17 A1.5 1.5 0 0 0 18.5 19 V8.5 Z", "M13.5 3.5 V8.5 H18.5")
    val FileCode = stroke("file-code", "M13.5 3.5 H7 A1.5 1.5 0 0 0 5.5 5 V19 A1.5 1.5 0 0 0 7 20.5 H17 A1.5 1.5 0 0 0 18.5 19 V8.5 Z", "M13.5 3.5 V8.5 H18.5", "M10 12 L8 14.5 L10 17", "M14 12 L16 14.5 L14 17")
    val Folder = stroke("folder", "M3.5 6.5 A1.5 1.5 0 0 1 5 5 H9.2 L11.2 7.6 H19 A1.5 1.5 0 0 1 20.5 9.1 V17.5 A1.5 1.5 0 0 1 19 19 H5 A1.5 1.5 0 0 1 3.5 17.5 Z")
    val FolderOpen = stroke("folder-open", "M3.5 8.5 V6.5 A1.5 1.5 0 0 1 5 5 H9.2 L11.2 7.6 H18 A1.5 1.5 0 0 1 19.5 9.1 V10", "M3.5 10 H20 L18.2 18.4 A1.5 1.5 0 0 1 16.7 19.5 H5.3 A1.5 1.5 0 0 1 3.8 18.1 Z")
    val Save = stroke("save", "M5 4.5 H15.5 L19.5 8.5 V19 A0.5 0.5 0 0 1 19 19.5 H5 A0.5 0.5 0 0 1 4.5 19 V5 A0.5 0.5 0 0 1 5 4.5 Z", "M8 4.5 V10 H15 V4.5", "M8 19.5 V13.5 H16 V19.5")

    // ---- Actions ----
    val Search = stroke("search", "M11 18.5 m-7 0 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0", "M16.2 16.2 L20.5 20.5")
    val Plus = stroke("plus", "M12 5 V19", "M5 12 H19")
    val Minus = stroke("minus", "M5 12 H19")
    val Check = stroke("check", "M5 12.5 L9.8 17.3 L19 7.5")
    val CheckCircle = stroke("check-circle", "M12 3.5 m-8.5 0 a8.5 8.5 0 1 0 17 0 a8.5 8.5 0 1 0 -17 0", "M8 12.2 L11 15.2 L16 9.6")
    val Copy = stroke("copy", "M8.5 8.5 H18 A1.5 1.5 0 0 1 19.5 10 V19 A1.5 1.5 0 0 1 18 20.5 H8.5 A1.5 1.5 0 0 1 7 19 V10 A1.5 1.5 0 0 1 8.5 8.5 Z", "M4.5 15.5 H4 A1.5 1.5 0 0 1 2.5 14 V5 A1.5 1.5 0 0 1 4 3.5 H14 A1.5 1.5 0 0 1 15.5 5 V5.5")
    val Share = stroke("share", "M12 15.5 V3.5", "M8 7.5 L12 3.5 L16 7.5", "M5 13.5 V19 A1.5 1.5 0 0 0 6.5 20.5 H17.5 A1.5 1.5 0 0 0 19 19 V13.5")
    val Download = stroke("download", "M12 3.5 V15.5", "M8 11.5 L12 15.5 L16 11.5", "M5 18 V19.5 A1 1 0 0 0 6 20.5 H18 A1 1 0 0 0 19 19.5 V18")
    val Upload = stroke("upload", "M12 20.5 V8.5", "M8 12.5 L12 8.5 L16 12.5", "M5 6 V4.5 A1 1 0 0 1 6 3.5 H18 A1 1 0 0 1 19 4.5 V6")
    val Refresh = stroke("refresh", "M20 12 A8 8 0 1 1 17.4 6.2", "M20.5 3.5 V7.5 H16.5")
    val External = stroke("external-link", "M14 4.5 H19.5 V10", "M19.5 4.5 L11.5 12.5", "M18 13.5 V19 A1.5 1.5 0 0 1 16.5 20.5 H5 A1.5 1.5 0 0 1 3.5 19 V7.5 A1.5 1.5 0 0 1 5 6 H10.5")
    val Trash = stroke("trash", "M4.5 6.5 H19.5", "M9.5 6.5 V4.5 A0.5 0.5 0 0 1 10 4 H14 A0.5 0.5 0 0 1 14.5 4.5 V6.5", "M6.5 6.5 L7.4 19.6 A1 1 0 0 0 8.4 20.5 H15.6 A1 1 0 0 0 16.6 19.6 L17.5 6.5")
    val Filter = stroke("filter", "M3.5 5.5 H20.5 L14 13 V19.5 L10 17 V13 Z")
    val Pencil = stroke("pencil", "M4 20 L4.9 15.9 L15.6 5.2 A2 2 0 0 1 18.4 5.2 L18.8 5.6 A2 2 0 0 1 18.8 8.4 L8.1 19.1 Z", "M14.4 6.4 L17.6 9.6")
    val Wand = stroke("wand", "M5 19 L15.5 8.5", "M13.4 6.4 L17.6 10.6", "M18.5 3 V6", "M17 4.5 H20", "M6 3 V5.5", "M4.75 4.25 H7.25", "M19 16 V18.5", "M17.75 17.25 H20.25")
    val Lock = stroke("lock", "M6.5 10.5 H17.5 A1.5 1.5 0 0 1 19 12 V19 A1.5 1.5 0 0 1 17.5 20.5 H6.5 A1.5 1.5 0 0 1 5 19 V12 A1.5 1.5 0 0 1 6.5 10.5 Z", "M8 10.5 V7.5 A4 4 0 0 1 16 7.5 V10.5")
    val Key = stroke("key", "M14.5 9.5 m-4.5 0 a4.5 4.5 0 1 0 9 0 a4.5 4.5 0 1 0 -9 0", "M11.2 12.8 L4 20", "M4 20 L4 17.5 H6.5", "M7.5 16.5 L7.5 14.5 H9.5")
    val Eye = stroke("eye", "M2.5 12 C5 7.5 8.4 5.5 12 5.5 C15.6 5.5 19 7.5 21.5 12 C19 16.5 15.6 18.5 12 18.5 C8.4 18.5 5 16.5 2.5 12 Z", "M12 12 m-2.8 0 a2.8 2.8 0 1 0 5.6 0 a2.8 2.8 0 1 0 -5.6 0")
    val Link = stroke("link", "M10 14 A4 4 0 0 0 15.7 14 L18.4 11.3 A4 4 0 0 0 12.7 5.6 L11.4 6.9", "M14 10 A4 4 0 0 0 8.3 10 L5.6 12.7 A4 4 0 0 0 11.3 18.4 L12.6 17.1")

    // ---- Build / run ----
    val Play = stroke("play", "M7.5 5.2 L18.5 12 L7.5 18.8 Z")
    val Hammer = stroke("hammer", "M14.5 4.5 L19.5 9.5", "M17 7 L19.5 4.5", "M12.4 6.6 L17.4 11.6 L15.6 13.4 L10.6 8.4 Z", "M9.4 9.6 L4 15 V19 H8 L13.4 13.6")
    val Bolt = stroke("bolt", "M13.5 2.5 L5 13.5 H11 L10.5 21.5 L19 10.5 H13 Z")
    val Terminal = stroke("terminal", "M3.5 5.5 H20.5 A1 1 0 0 1 21.5 6.5 V17.5 A1 1 0 0 1 20.5 18.5 H3.5 A1 1 0 0 1 2.5 17.5 V6.5 A1 1 0 0 1 3.5 5.5 Z", "M6 9.5 L9 12.2 L6 14.9", "M11.5 15 H17")
    val Bug = stroke("bug", "M8 8.5 H16 V14.5 A4 4 0 0 1 12 18.5 A4 4 0 0 1 8 14.5 Z", "M9.5 8.5 V7 A2.5 2.5 0 0 1 14.5 7 V8.5", "M8 11 H4.5", "M8 15.5 L5 18", "M16 11 H19.5", "M16 15.5 L19 18")
    val Server = stroke("server", "M3.5 4.5 H20.5 V10 H3.5 Z", "M3.5 14 H20.5 V19.5 H3.5 Z", "M7 7.25 H7.02", "M7 16.75 H7.02", "M11 7.25 H15", "M11 16.75 H15")

    // ---- Status ----
    val Alert = stroke("alert", "M12 3.8 L21 19.5 H3 Z", "M12 10 V14", "M12 16.6 H12.02")
    val Info = stroke("info", "M12 3.5 m-8.5 0 a8.5 8.5 0 1 0 17 0 a8.5 8.5 0 1 0 -17 0", "M12 11 V16.5", "M12 7.9 H12.02")
    val Warning = stroke("warning", "M12 3.5 m-8.5 0 a8.5 8.5 0 1 0 17 0 a8.5 8.5 0 1 0 -17 0", "M12 7.5 V13", "M12 16 H12.02")
    val Clock = stroke("clock", "M12 3.5 m-8.5 0 a8.5 8.5 0 1 0 17 0 a8.5 8.5 0 1 0 -17 0", "M12 7 V12.4 L15.5 14.5")
    val History = stroke("history", "M3.5 12 A8.5 8.5 0 1 0 6.2 5.9", "M3.5 4.5 V8.5 H7.5", "M12 8 V12.4 L15.5 14.5")

    // ---- AI / agent ----
    val Sparkle = solid("sparkle", "M12 2.5 L14.1 8.6 L20.5 10.6 L14.1 12.7 L12 18.8 L9.9 12.7 L3.5 10.6 L9.9 8.6 Z", "M18.5 15 L19.6 18 L22.5 19 L19.6 20 L18.5 23 L17.4 20 L14.5 19 L17.4 18 Z")
    val SparkleStroke = stroke("sparkle-stroke", "M12 3.2 L14 8.8 L19.6 10.8 L14 12.8 L12 18.4 L10 12.8 L4.4 10.8 L10 8.8 Z", "M18.6 15.4 L19.6 18.1 L22.3 19.1 L19.6 20.1 L18.6 22.8 L17.6 20.1 L14.9 19.1 L17.6 18.1 Z")
    val Robot = stroke("robot", "M5 8.5 H19 A1.5 1.5 0 0 1 20.5 10 V17 A1.5 1.5 0 0 1 19 18.5 H5 A1.5 1.5 0 0 1 3.5 17 V10 A1.5 1.5 0 0 1 5 8.5 Z", "M12 4 V8.5", "M12 3.2 m-1.2 0 a1.2 1.2 0 1 0 2.4 0 a1.2 1.2 0 1 0 -2.4 0", "M9 12.5 V14", "M15 12.5 V14", "M8.5 18.5 V20.5", "M15.5 18.5 V20.5")
    val Shield = stroke("shield", "M12 3.2 L19.5 6 V11.5 C19.5 16.4 16.2 19.6 12 20.8 C7.8 19.6 4.5 16.4 4.5 11.5 V6 Z", "M9 12 L11.3 14.3 L15.3 10.2")

    // ---- Identity ----
    val User = stroke("user", "M12 11.5 m-4 0 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0", "M4.5 20.5 C4.5 16.7 7.9 14.5 12 14.5 C16.1 14.5 19.5 16.7 19.5 20.5")
    val Crown = solid("crown", "M3 18.5 L4.6 7.5 L8.7 12 L12 5.5 L15.3 12 L19.4 7.5 L21 18.5 Z")
    val CrownStroke = stroke("crown-stroke", "M3.2 18.2 L4.8 7.8 L8.8 12.2 L12 5.8 L15.2 12.2 L19.2 7.8 L20.8 18.2 Z")
    val Building = stroke("building", "M4.5 20.5 V5 A1.5 1.5 0 0 1 6 3.5 H14 A1.5 1.5 0 0 1 15.5 5 V20.5", "M15.5 9.5 H18 A1.5 1.5 0 0 1 19.5 11 V20.5", "M3 20.5 H21", "M8 7.5 H11", "M8 11.5 H11", "M8 15.5 H11")
    val Star = stroke("star", "M12 3.5 L14.6 9 L20.5 9.8 L16.2 13.9 L17.3 19.8 L12 16.9 L6.7 19.8 L7.8 13.9 L3.5 9.8 L9.4 9 Z")
    val Grid = stroke("grid", "M3.5 3.5 H10 V10 H3.5 Z", "M14 3.5 H20.5 V10 H14 Z", "M3.5 14 H10 V20.5 H3.5 Z", "M14 14 H20.5 V20.5 H14 Z")
    val Settings = stroke("settings", "M12 15 m-3 0 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0", "M19.4 15 A1.7 1.7 0 0 0 19.7 16.9 L19.8 17 A2 2 0 1 1 17 19.8 L16.9 19.7 A1.7 1.7 0 0 0 15 19.4 A1.7 1.7 0 0 0 13.9 20.9 V21 A2 2 0 1 1 10.1 21 V20.9 A1.7 1.7 0 0 0 9 19.4 A1.7 1.7 0 0 0 7.1 19.7 L7 19.8 A2 2 0 1 1 4.2 17 L4.3 16.9 A1.7 1.7 0 0 0 4.6 15 A1.7 1.7 0 0 0 3.1 13.9 H3 A2 2 0 1 1 3 10.1 H3.1 A1.7 1.7 0 0 0 4.6 9 A1.7 1.7 0 0 0 4.3 7.1 L4.2 7 A2 2 0 1 1 7 4.2 L7.1 4.3 A1.7 1.7 0 0 0 9 4.6 A1.7 1.7 0 0 0 10.1 3.1 V3 A2 2 0 1 1 13.9 3 V3.1 A1.7 1.7 0 0 0 15 4.6 A1.7 1.7 0 0 0 16.9 4.3 L17 4.2 A2 2 0 1 1 19.8 7 L19.7 7.1 A1.7 1.7 0 0 0 19.4 9 A1.7 1.7 0 0 0 20.9 10.1 H21 A2 2 0 1 1 21 13.9 H20.9 A1.7 1.7 0 0 0 19.4 15 Z")

    // ---- Network ----
    val Cloud = stroke("cloud", "M7 18.5 A4.5 4.5 0 0 1 7.4 9.6 A5.5 5.5 0 0 1 18.2 10.6 A4 4 0 0 1 17.5 18.5 Z")
    val Wifi = stroke("wifi", "M2.5 9 C5.6 6 8.7 4.5 12 4.5 C15.3 4.5 18.4 6 21.5 9", "M6 12.8 C7.9 11 9.9 10.1 12 10.1 C14.1 10.1 16.1 11 18 12.8", "M9.3 16.4 C10.1 15.6 11 15.2 12 15.2 C13 15.2 13.9 15.6 14.7 16.4", "M12 19.6 H12.02")
    val Globe = stroke("globe", "M12 3.5 m-8.5 0 a8.5 8.5 0 1 0 17 0 a8.5 8.5 0 1 0 -17 0", "M3.5 12 H20.5", "M12 3.5 C14.5 6 15.8 8.9 15.8 12 C15.8 15.1 14.5 18 12 20.5", "M12 3.5 C9.5 6 8.2 8.9 8.2 12 C8.2 15.1 9.5 18 12 20.5")

    // ---- Misc ----
    val Code = stroke("code", "M8.5 7 L3.5 12 L8.5 17", "M15.5 7 L20.5 12 L15.5 17")
    val Tag = stroke("tag", "M11.4 3.5 H19 A1.5 1.5 0 0 1 20.5 5 V12.6 L12.6 20.5 L3.5 11.4 L11.4 3.5 Z", "M16 8 H16.02")
    val Layers = stroke("layers", "M12 3.5 L21 8.5 L12 13.5 L3 8.5 Z", "M3 12.5 L12 17.5 L21 12.5", "M3 16.5 L12 21.5 L21 16.5")
    val Rocket = stroke("rocket", "M14.5 3.5 C17.5 4.5 19.5 6.5 20.5 9.5 L12 18 L6 12 Z", "M6 12 L4 10 V14 L6 12", "M12 18 L14 20 H10 L12 18", "M9 15 L6.5 17.5", "M15.5 8.5 m-1.4 0 a1.4 1.4 0 1 0 2.8 0 a1.4 1.4 0 1 0 -2.8 0")
    val Zap = stroke("zap", "M4 14 L13 3 L11 10 H20 L11 21 L13 14 Z")
    val Bell = stroke("bell", "M6.5 16.5 V10.5 A5.5 5.5 0 0 1 17.5 10.5 V16.5 L19 18.5 H5 Z", "M10 18.5 A2 2 0 0 0 14 18.5")
    val Palette = stroke("palette", "M12 3.5 C7.3 3.5 3.5 7 3.5 11.4 C3.5 15.8 7 18.6 11 18.6 C12.4 18.6 13 18 13 17.2 C13 16.3 12.2 15.8 12.2 14.8 C12.2 13.7 13.1 12.9 14.3 12.9 H16.2 C18.5 12.9 20.5 11.4 20.5 8.9 C20.5 5.7 16.8 3.5 12 3.5 Z", "M7.5 9 H7.52", "M11 7 H11.02", "M15.5 8 H15.52", "M6.5 13 H6.52")
    val Scroll = stroke("scroll", "M12 3.5 H18.5 A2 2 0 0 1 20.5 5.5 V17.5 A3 3 0 0 0 23.5 20.5", "M12 3.5 A2 2 0 0 0 10 5.5 V17.5 A3 3 0 0 1 7 20.5 H13.5 A3 3 0 0 0 16.5 17.5", "M5 20.5 A3 3 0 0 1 2 17.5 V5.5 A2 2 0 0 1 4 3.5 H10")

    val all: List<ImageVector> = listOf(
        ChevronRight, ChevronLeft, ChevronDown, ChevronUp, ArrowRight, ArrowLeft, ArrowUp, ArrowDown,
        Menu, Close, More, MoreHorizontal, Branch, Commit, PullRequest, Merge, Diff, File, FileCode,
        Folder, FolderOpen, Save, Search, Plus, Minus, Check, CheckCircle, Copy, Share, Download, Upload,
        Refresh, External, Trash, Filter, Pencil, Wand, Lock, Key, Eye, Link, Play, Hammer, Bolt,
        Terminal, Bug, Server, Alert, Info, Warning, Clock, History, Sparkle, SparkleStroke, Robot,
        Shield, User, Crown, CrownStroke, Building, Star, Grid, Settings, Cloud, Wifi, Globe, Code, Tag,
        Layers, Rocket, Zap, Bell, Palette, Scroll,
    )
}
