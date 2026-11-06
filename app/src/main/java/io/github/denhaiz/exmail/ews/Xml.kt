package io.github.denhaiz.exmail.ews

import org.w3c.dom.Element
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import javax.xml.parsers.DocumentBuilderFactory

/** Лёгкая обёртка над DOM-элементом: доступ к детям по localName без учёта префиксов. */
class XmlNode(val element: Element) {
    val name: String get() = element.localName ?: element.tagName.substringAfter(':')

    val children: List<XmlNode>
        get() {
            val nodes = element.childNodes
            val result = ArrayList<XmlNode>(nodes.length)
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                if (n is Element) result += XmlNode(n)
            }
            return result
        }

    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }

    fun children(name: String): List<XmlNode> = children.filter { it.name == name }

    fun at(vararg path: String): XmlNode? {
        var cur: XmlNode? = this
        for (p in path) cur = cur?.child(p)
        return cur
    }

    val text: String get() = element.textContent ?: ""

    /** Текст по пути из дочерних элементов или null, если элемента нет. */
    fun text(vararg path: String): String? = at(*path)?.text

    fun bool(vararg path: String): Boolean = text(*path)?.trim().equals("true", ignoreCase = true)

    fun int(vararg path: String): Int? = text(*path)?.trim()?.toIntOrNull()

    fun instant(vararg path: String): Instant? = text(*path)?.let(Xml::parseInstant)

    fun attr(name: String): String? = element.getAttribute(name).takeIf { it.isNotEmpty() }

    /** Первый потомок с таким именем (обход в глубину). */
    fun find(name: String): XmlNode? {
        for (c in children) {
            if (c.name == name) return c
            c.find(name)?.let { return it }
        }
        return null
    }

    fun findAll(name: String): List<XmlNode> {
        val out = mutableListOf<XmlNode>()
        fun walk(n: XmlNode) {
            for (c in n.children) {
                if (c.name == name) out += c
                walk(c)
            }
        }
        walk(this)
        return out
    }
}

object Xml {
    private val factory: DocumentBuilderFactory by lazy {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
    }

    fun parse(input: InputStream): XmlNode = XmlNode(factory.newDocumentBuilder().parse(input).documentElement)

    fun parse(text: String): XmlNode = parse(text.byteInputStream(Charsets.UTF_8))

    fun esc(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\r' || ch == '\t') sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun parseInstant(s: String): Instant? {
        val t = s.trim()
        if (t.isEmpty()) return null
        return try {
            Instant.parse(t)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(t).toInstant()
            } catch (_: DateTimeParseException) {
                // EWS отдаёт время без смещения в UTC (например, период автоответа)
                runCatching { java.time.LocalDateTime.parse(t).toInstant(java.time.ZoneOffset.UTC) }.getOrNull()
            }
        }
    }

    /** Формат времени для EWS: UTC с суффиксом Z, без долей секунды. */
    fun formatInstant(i: Instant): String = i.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
}
