package com.jigu.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jigu.app.ui.theme.PoemTextStyle
import com.jigu.app.ui.theme.Wenkai
import androidx.compose.ui.unit.sp

/**
 * 统一诗文排版（D-012 定案的测试版）：
 * - 诗词：断句 → 「X，Y。」等长联句合一行 → 其余按句一行，全诗居中成柱；长段（小序/杂言）回退左对齐段
 * - 古文/语录/蒙学（PROSE）：逐段左对齐，段距大于行距
 * 标题与署名由调用方居中。
 */

private val PROSE_CATEGORIES = setOf("guwen", "lunyu", "sishu", "mengxue", "youmengying")

private val TERM = setOf('，', '。', '！', '？', '；', '：', '、', ',', '.', ';', ':')

private fun String.plainLen(): Int = count { it !in TERM && !it.isWhitespace() }

/** 一段 → 展示行列表：等长双句合并，长杂句按"，"分半，句末标点保留 */
fun paragraphToLines(paragraph: String): List<String> {
    // 句 = 以 。！？ 收束的整句（保留标点）
    val sentences = Regex("(?<=[。！？])").split(paragraph.trim())
        .map { it.trim() }.filter { it.isNotEmpty() }
    val out = mutableListOf<String>()
    for (sentence in sentences) {
        val parts = sentence.split('，')
        if (parts.size == 2 && parts[0].plainLen() == parts[1].plainLen() && parts[0].plainLen() in 4..12) {
            out += sentence
        } else {
            parts.forEachIndexed { i, part ->
                out += if (i < parts.lastIndex) part.trim().trimEnd('，') + "，" else part.trim()
            }
        }
    }
    return out
}

@Composable
fun PoemBody(paragraphs: List<String>, category: String, modifier: Modifier = Modifier) {
    if (category in PROSE_CATEGORIES) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            paragraphs.forEach { para ->
                Text(
                    text = para,
                    style = PoemTextStyle.copy(lineHeight = 44.sp),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        return
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        paragraphs.forEach { para ->
            val lines = paragraphToLines(para)
            // 段落太长（小序/歌行）：整段作左对齐散文块，不打碎
            if (lines.size > 10) {
                Text(
                    text = para,
                    style = PoemTextStyle.copy(lineHeight = 44.sp, fontFamily = Wenkai),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = PoemTextStyle,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
