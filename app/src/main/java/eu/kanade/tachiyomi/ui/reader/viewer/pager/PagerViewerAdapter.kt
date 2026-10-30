package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import tachiyomi.core.common.util.system.logcat

/**
 * Pager adapter used by this [viewer] to where [ViewerChapters] updates are posted.
 */
class PagerViewerAdapter(private val viewer: PagerViewer) : ViewPagerAdapter() {

    /**
     * List of currently set items.
     */
    var items: MutableList<Any> = mutableListOf()
        private set

    /**
     * Holds preprocessed items so they don't get removed when changing chapter
     */
    private var preprocessed: List<InsertPage> = emptyList()

    var nextTransition: ChapterTransition.Next? = null
        private set

    var currentChapter: ReaderChapter? = null

    // ---- Custom: two pages side by side (landscape) ----

    /** Items in reading order before pairing, kept to re-pair when a wide page is found. */
    private var readingOrderItems: List<Any> = emptyList()

    /** First page of a pair -> second page of the pair. */
    val partners = HashMap<ReaderPage, ReaderPage>()

    /** Pages that must be shown alone (wide spreads, animated images). */
    private val singles = HashSet<ReaderPage>()

    /** Page the pairs are aligned to: the page being viewed when the screen was rotated. */
    var dualAnchor: ReaderPage? = null

    /** Whether the items are currently paired. */
    var isDual = false
        private set

    fun partnerOf(page: ReaderPage): ReaderPage? = if (isDual) partners[page] else null

    /** Returns the item that shows [page], which is the page itself or the first page of its pair. */
    fun itemFor(page: ReaderPage): Any? {
        if (items.contains(page)) return page
        return partners.entries.firstOrNull { it.value === page }?.key
    }

    private fun pairUp(list: List<Any>): List<Any> {
        partners.clear()
        val out = mutableListOf<Any>()
        var i = 0
        while (i < list.size) {
            val item = list[i]
            if (item !is ReaderPage || item is InsertPage) {
                out.add(item)
                i++
                continue
            }
            // A run of pages of the same chapter
            var j = i
            while (j < list.size) {
                val next = list[j]
                if (next !is ReaderPage || next is InsertPage || next.chapter !== item.chapter) break
                j++
            }
            @Suppress("UNCHECKED_CAST")
            val run = list.subList(i, j) as List<ReaderPage>
            out.addAll(pairRun(run))
            i = j
        }
        return out
    }

    private fun pairRun(run: List<ReaderPage>): List<ReaderPage> {
        val anchor = run.indexOfFirst { it === dualAnchor }.coerceAtLeast(0)
        val after = mutableListOf<ReaderPage>()
        var k = anchor
        while (k < run.size) {
            val a = run[k]
            val b = run.getOrNull(k + 1)
            if (b != null && a !in singles && b !in singles) {
                partners[a] = b
                after.add(a)
                k += 2
            } else {
                after.add(a)
                k += 1
            }
        }
        val before = mutableListOf<ReaderPage>()
        k = anchor - 1
        while (k >= 0) {
            val b = run[k]
            val a = run.getOrNull(k - 1)
            if (a != null && a !in singles && b !in singles) {
                partners[a] = b
                before.add(0, a)
                k -= 2
            } else {
                before.add(0, b)
                k -= 1
            }
        }
        return before + after
    }

    private fun applyItems(readingOrder: List<Any>) {
        readingOrderItems = readingOrder
        isDual = viewer.dualEnabled()
        val newItems = if (isDual) pairUp(readingOrder).toMutableList() else readingOrder.toMutableList()
        if (!isDual) partners.clear()
        if (viewer is R2LPagerViewer) {
            newItems.reverse()
        }
        items = newItems
    }

    /** Re-pairs pages, keeping the pair that shows [keep] on screen. */
    fun rebuildDual(keep: ReaderPage?) {
        if (readingOrderItems.isEmpty()) return
        if (keep != null) dualAnchor = keep
        applyItems(readingOrderItems)
        notifyDataSetChanged()
        val target = keep?.let { itemFor(it) }
        val position = target?.let { items.indexOf(it) } ?: -1
        if (position != -1 && position != viewer.pager.currentItem) {
            viewer.pager.setCurrentItem(position, false)
        }
    }

    /** A wide (or animated) page was found inside a pair: show it alone from now on. */
    fun markSingle(page: ReaderPage, keep: ReaderPage?) {
        if (!singles.add(page)) return
        rebuildDual(keep)
    }

    /**
     * Context that has been wrapped to use the correct theme values based on the
     * current app theme and reader background color
     */
    private var readerThemedContext = viewer.activity.createReaderThemeContext()

    /**
     * Updates this adapter with the given [chapters]. It handles setting a few pages of the
     * next/previous chapter to allow seamless transitions and inverting the pages if the viewer
     * has R2L direction.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean) {
        val newItems = mutableListOf<Any>()

        // Forces chapter transition if there is missing chapters
        val prevHasMissingChapters = calculateChapterGap(chapters.currChapter, chapters.prevChapter) > 0
        val nextHasMissingChapters = calculateChapterGap(chapters.nextChapter, chapters.currChapter) > 0

        // Add previous chapter pages and transition
        chapters.prevChapter?.pages?.let(newItems::addAll)

        // Skip transition page if the chapter is loaded & current page is not a transition page
        if (prevHasMissingChapters || forceTransition || chapters.prevChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
        }

        var insertPageLastPage: InsertPage? = null

        // Add current chapter.
        val currPages = chapters.currChapter.pages
        if (currPages != null) {
            val pages = currPages.toMutableList()

            val lastPage = pages.last()

            // Insert preprocessed pages into current page list. Pages of the adjacent chapters are split too, and
            // placed by their index, so only this chapter's belong here.
            preprocessed.filter { it.chapter.chapter.id == chapters.currChapter.chapter.id }
                .sortedByDescending { it.index }
                .forEach { page ->
                    if (lastPage.index == page.index) {
                        insertPageLastPage = page
                    }
                    pages.add(page.index + 1, page)
                }

            newItems.addAll(pages)
        }

        currentChapter = chapters.currChapter

        // Add next chapter transition and pages.
        nextTransition = ChapterTransition.Next(chapters.currChapter, chapters.nextChapter)
            .also {
                if (
                    nextHasMissingChapters ||
                    forceTransition ||
                    chapters.nextChapter?.state !is ReaderChapter.State.Loaded
                ) {
                    newItems.add(it)
                }
            }

        chapters.nextChapter?.pages?.let(newItems::addAll)

        val currentItem = items.getOrNull(viewer.pager.currentItem)

        // Resets double-page splits, else insert pages get misplaced
        items.filterIsInstance<InsertPage>().also { items.removeAll(it) }

        val adjacentChapterIds = setOfNotNull(chapters.prevChapter?.chapter?.id, chapters.nextChapter?.chapter?.id)
        preprocessed = preprocessed.filter { it.chapter.chapter.id in adjacentChapterIds }
        applyItems(newItems)
        notifyDataSetChanged()

        // The pager only follows the pages it has created, so before its first layout it would stay at the same
        // position while pages are added in front, e.g. the next chapter's in right to left, and land in another
        // chapter
        val currentPosition = (currentItem as? ReaderPage)?.let { itemFor(it) }?.let { items.indexOf(it) }
            ?: items.indexOf(currentItem)
        if (currentPosition != -1 && currentPosition != viewer.pager.currentItem) {
            viewer.pager.setCurrentItem(currentPosition, false)
        }

        // Will skip insert page otherwise
        if (insertPageLastPage != null) {
            viewer.moveToPage(insertPageLastPage)
        }
    }

    /**
     * Returns the amount of items of the adapter.
     */
    override fun getCount(): Int {
        return items.size
    }

    /**
     * Creates a new view for the item at the given [position].
     */
    override fun createView(container: ViewGroup, position: Int): View {
        return when (val item = items[position]) {
            is ReaderPage -> PagerPageHolder(readerThemedContext, viewer, item, partnerOf(item))
            is ChapterTransition -> PagerTransitionHolder(readerThemedContext, viewer, item)
            else -> throw NotImplementedError("Holder for ${item.javaClass} not implemented")
        }
    }

    /**
     * Returns the current position of the given [view] on the adapter.
     */
    override fun getItemPosition(view: Any): Int {
        if (view is PagerPageHolder && view.partner !== partnerOf(view.page)) {
            return POSITION_NONE
        }
        if (view is PositionableView) {
            val position = items.indexOf(view.item)
            if (position != -1) {
                return position
            } else {
                logcat { "Position for ${view.item} not found" }
            }
        }
        return POSITION_NONE
    }

    fun onPageSplit(currentPage: Any?, newPage: InsertPage) {
        if (currentPage !is ReaderPage) return

        val currentIndex = items.indexOf(currentPage)

        // Put aside pages of an adjacent chapter until it becomes the current one, so they are not lost on the change
        if (currentPage.chapter.chapter.id != currentChapter?.chapter?.id) {
            val chapterId = newPage.chapter.chapter.id
            if (preprocessed.none { it.chapter.chapter.id == chapterId && it.index == newPage.index }) {
                preprocessed = preprocessed + newPage
            }
            return
        }

        val placeAtIndex = when (viewer) {
            is L2RPagerViewer,
            is VerticalPagerViewer,
            -> currentIndex + 1
            else -> currentIndex
        }

        // It will enter a endless cycle of insert pages
        if (viewer is R2LPagerViewer && placeAtIndex - 1 >= 0 && items[placeAtIndex - 1] is InsertPage) {
            return
        }

        // Same here it will enter a endless cycle of insert pages
        if (items[placeAtIndex] is InsertPage) {
            return
        }

        items.add(placeAtIndex, newPage)

        notifyDataSetChanged()
    }

    fun cleanupPageSplit() {
        val insertPages = items.filterIsInstance<InsertPage>()
        items.removeAll(insertPages)
        notifyDataSetChanged()
    }

    fun refresh() {
        readerThemedContext = viewer.activity.createReaderThemeContext()
    }
}
