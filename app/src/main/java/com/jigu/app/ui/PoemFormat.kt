package com.jigu.app.ui

import com.jigu.app.data.PoemsDb

/** 署名归一（D-012）：全 App 统一「朝代 · 作者」；带题版「朝代 · 作者《题》」。
 * 此前今日页/详情/分享卡各写一套（还曾把意象串、异文行误读到署名位），此处收敛单一出口。 */

fun poemDynasty(p: PoemsDb.Poem): String = p.dynasty.ifBlank { p.era }

fun authorName(p: PoemsDb.Poem): String = p.author.ifBlank { "佚名" }

/** 「朝代 · 作者」（朝代缺省时只留作者名） */
fun signatureOf(p: PoemsDb.Poem): String =
    listOfNotNull(
        poemDynasty(p).takeIf { it.isNotBlank() },
        authorName(p),
    ).joinToString(" · ")

/** 「朝代 · 作者《题》」——今日页名句署名与分享卡出处 */
fun signatureWithTitle(p: PoemsDb.Poem): String = "${signatureOf(p)}《${p.title}》"

/** 署名下的小字补充（不挤占署名行）：诗经/楚辞的卷·节，古文的文体 */
fun attributionSubtitle(p: PoemsDb.Poem): String = when (p.category) {
    "shijing", "chuci" -> listOfNotNull(
        p.chapter.takeIf { it.isNotBlank() },
        p.section.takeIf { it.isNotBlank() },
    ).joinToString("·")
    "guwen" -> p.genre
    else -> ""
}
