/*
 *  Copyright (C) 2023  WirelessAlien <https://github.com/WirelessAlien>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.wirelessalien.zipxtract.helper

import java.io.File
import java.util.Locale

object MimeTypeHelper {

    private val nonArchiveExtensions = setOf(
        // Documents
        "doc", "dot", "pdf", "rtf", "xls", "xlb", "xlt", "xlam", "xlsb", "xlsm", "xltm", "thmx",
        "ppt", "pps", "ppam", "pptm", "sldm", "ppsm", "potm", "docm", "dotm", "odc", "odb", "odf",
        "odg", "otg", "odi", "odp", "otp", "ods", "ots", "odt", "odm", "ott", "oth", "pptx", "sldx",
        "ppsx", "potx", "xlsx", "xltx", "docx", "dotx", "sdc", "sds", "sda", "sdd", "sdf", "sdw",
        "sgl", "sxc", "stc", "sxd", "std", "sxi", "sti", "sxm", "sxw", "sxg", "stw", "vsd", "vst",
        "vsw", "vss", "wpd", "wp5", "abw", "gnumeric", "hwp", "kpr", "kpt", "ksp", "kwd", "kwt",
        "latex", "lyx", "texinfo", "texi", "t", "tr", "roff", "man",

        // Code & Web
        "atom", "es", "ser", "class", "js", "json", "rss", "xhtml", "xht", "xml", "xsd", "xsl",
        "xslt", "com", "exe", "bat", "dll", "pyc", "pyo", "rb", "sh", "tcl", "css", "csv", "html",
        "htm", "shtml", "md", "markdown", "mml", "asc", "txt", "text", "pot", "brf", "srt",
        "vcf", "vcard", "wml", "wmls", "c++", "cpp", "cxx", "cc", "h++", "hpp", "hxx", "hh", "c",
        "java", "pl", "pm", "py", "sh", "kt",

        // Images
        "gif", "ief", "jp2", "jpg2", "jpeg", "jpg", "jpe", "jpm", "jpx", "jpf", "pcx", "png",
        "svg", "svgz", "tiff", "tif", "djvu", "djv", "ico", "wbmp", "cr2", "crw", "cdr", "cpt",
        "bmp", "nef", "orf", "psd", "pnm", "pbm", "pgm", "ppm", "rgb", "xbm", "xpm", "xwd",

        // Audio
        "amr", "awb", "au", "snd", "flac", "mid", "midi", "kar", "mpga", "mpega", "mp2", "mp3",
        "m4a", "oga", "ogg", "opus", "spx", "aif", "aiff", "aifc", "gsm", "m3u", "m3u8", "wma",
        "wax", "ra", "rm", "ram", "wav",

        // Video
        "3gp", "axv", "dif", "dv", "fli", "mpeg", "mpg", "mpe", "ts", "mp4", "qt", "mov", "ogv",
        "webm", "flv", "mpv", "mkv", "asf", "asx", "wm", "wmv", "avi",

        // Fonts
        "otf", "ttf", "woff", "ttc", "woff2",

        // Scientific, Chemical & Models
        "chm", "cif", "gau", "gjc", "gjf", "pdb", "ent", "xyz", "igs", "iges", "msh", "mesh",
        "silo", "wrl", "vrml", "x3dv", "x3d",

        // Other Applications
        "ez", "dcm", "spl", "nb", "nbp", "mbox", "mdb", "mxf", "one", "onetoc2", "pgp", "key",
        "sig", "ps", "ai", "eps", "kml", "kmz", "pgn", "dcr", "dir", "dxr", "swf", "swfl"
    )

    fun isNonArchive(file: File): Boolean {
        val extension = file.extension.lowercase(Locale.getDefault())
        return nonArchiveExtensions.contains(extension)
    }
}
