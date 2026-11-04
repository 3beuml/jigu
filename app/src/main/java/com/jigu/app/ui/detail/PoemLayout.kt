package com.jigu.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jigu.app.ui.theme.PoemTextStyle
import com.jigu.app.ui.theme.Wenkai

/**
 * 统一诗文排版（P排-1~3 落地；规则草案与外部参照见 docs/RESEARCH-2026-11-04-诗文排版参照.md）：
 * - 诗词/词曲/楚辞：断句 → 句读组（。！？收束的整句）整句成行（≤18 字；词按韵断成行，
 *   取法龙榆生谱式，根治 「转朱阁，／低绮户，／照无眠。」 式碎行，P排-1）
 *   超长句（衬字/长调）退回逐逗拆行 → 全诗居中成柱。
 * - 章/段两级间距：多行段=诗经章/组诗一首，段距 22dp > 行距 6dp（P排-1）。
 * - 行尾句读行右移 1/4 字校轴：全角句读墨迹居字面左半，居中轴观感偏左，回正不加悬挂（P排-2）。
 * - 字号统一由最长行+屏宽定（下限 18sp，保证一行不折行）；单段长篇（歌行/长序）保留行数降档（P1-5）。
 * - 小序/长段：缩进两字、小一档的左对齐块（P排-3）。
 * - 古文/语录/蒙学（PROSE）：逐段左对齐、段首缩进两字（P排-3），段距大于行距。
 * 标题与署名由调用方居中。
 */

private val PROSE_CATEGORIES = setOf("guwen", "laozi", "zhuangzi", "shishuo", "shiji", "lunyu", "sishu", "mengxue", "youmengying")

private val TERM = setOf('，', '。', '！', '？', '；', '：', '、', ',', '.', ';', ':')

/** 段首缩进用两枚全角空格：离线只读，复制带缩进可接受（P排-3 实现备案） */
private const val INDENT = "　　"

private fun String.plainLen(): Int = count { it !in TERM && !it.isWhitespace() }

/** 一段 → 展示行列表：句读组整句成行（P排-1，句长 ≤ maxSentence 实字），超长句逐逗拆行（非末逗保留），
 * 无逗可拆的衬字长句按定长硬切兜底 */
fun paragraphToLines(paragraph: String, maxSentence: Int = 18): List<String> {
    // 句 = 以 。！？ 收束的整句（保留标点）
    val sentences = Regex("(?<=[。！？])").split(paragraph.trim())
        .map { it.trim() }.filter { it.isNotEmpty() }
    val out = mutableListOf<String>()
    for (sentence in sentences) {
        val plain = sentence.plainLen()
        when {
            plain == 0 -> {}
            plain <= maxSentence -> out += sentence
            else -> {
                val parts = sentence.split('，').filter { it.isNotBlank() }
                if (parts.size == 1) {
                    out += sentence.chunked(maxSentence + 2)
                } else {
                    parts.forEachIndexed { i, part ->
                        out += if (i < parts.lastIndex) part.trim().trimEnd('，') + "，" else part.trim()
                    }
                }
            }
        }
    }
    return out
}

/** 字号自适应：屏宽放不下最长行（含标点列）时收缩；长篇（行数降档，P1-5）仅当整篇为单段歌行体
 * 才启用（组诗/词句组建行不受行数影响，避免误缩）；下限 18sp——20 列句 @18sp 仍不折行 */
fun poemFontSize(screenWidthDp: Float, maxLineLen: Int, lineCount: Int, longForm: Boolean = false): Int {
    var size = 25
    if (longForm && lineCount > 28) size = 19 else if (longForm && lineCount > 18) size = 21
    val fit = ((screenWidthDp - 48f) / (maxLineLen + 1.5f)).toInt()
    if (fit > 0) size = size.coerceAtMost(fit)
    return size.coerceAtLeast(18)
}

@Composable
fun PoemBody(paragraphs: List<String>, category: String, modifier: Modifier = Modifier) {
    if (category in PROSE_CATEGORIES) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            paragraphs.forEach { para ->
                Text(
                    text = INDENT + para,
                    style = PoemTextStyle.copy(lineHeight = 43.sp),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        return
    }
    BoxWithConstraints(modifier = modifier) {
        val linesList = paragraphs.map { paragraphToLines(it) }
        val lineCount = linesList.sumOf { it.size }
        val maxColumns = linesList.flatten().maxOfOrNull { it.length } ?: 10
        // 长篇判定：总长超 P1-5 的 160 且平均每段 >3 行（歌行/长序），组诗/词仍走统一 fit
        val longForm = paragraphs.sumOf { it.length } > 160 && lineCount > 3 * paragraphs.size
        val size = poemFontSize(maxWidth.value, maxColumns, lineCount, longForm)
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            linesList.forEachIndexed { index, lines ->
                Column(
                    modifier = if (index > 0 && lines.size > 1) Modifier.padding(top = 16.dp) else Modifier,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (lines.size > 10) {
                        // 小序/长段：小一档、缩进两字的左对齐块（P排-3），不打碎
                        val proseSize = (size - 4).coerceAtLeast(16)
                        Text(
                            text = INDENT + paragraphs[index],
                            style = PoemTextStyle.copy(
                                fontSize = proseSize.sp,
                                lineHeight = (proseSize * 1.7).sp,
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        lines.forEach { line -> PoemLine(line, size) }
                    }
                }
            }
        }
    }
}

/** 居中诗行（P排-2）：行尾句读的墨迹占字面左半，右半空格把视觉重心拖偏——整行右移 1/4 字回正，
 * 与居中标题/署名的墨迹中轴对齐；非悬挂做法（clreq：中文出版界主流不悬挂） */
@Composable
private fun PoemLine(line: String, size: Int) {
    val tail = line.lastOrNull()
    val shift = with(LocalDensity.current) { (size / 4).sp.toDp() }
    Text(
        text = line,
        style = PoemTextStyle.copy(
            fontSize = size.sp,
            lineHeight = (size * 1.8).sp,
        ),
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = if (tail != null && tail in TERM) Modifier.offset(x = shift) else Modifier,
    )
}
