package eu.kanade.tachiyomi.ui.library

import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.f2prateek.rx.preferences.Preference
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFilterable
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.widget.AutofitRecyclerView
import kotlinx.android.synthetic.main.catalogue_grid_item.view.*

class LibraryItem(val manga: LibraryManga, private val libraryAsList: Preference<Boolean>) :
        AbstractFlexibleItem<LibraryHolder>(), IFilterable<String> {
    var downloadCount = -1

    override fun getLayoutRes(): Int {
        return if (libraryAsList.getOrDefault())
            R.layout.catalogue_list_item
        else
            R.layout.catalogue_grid_item
    }

    override fun createViewHolder(view: View, adapter: FlexibleAdapter<IFlexible<androidx.recyclerview.widget.RecyclerView.ViewHolder>>): LibraryHolder {
        val parent = adapter.recyclerView
        return if (parent is AutofitRecyclerView) {
            view.apply {
                val coverHeight = parent.itemWidth / 3 * 4
                card.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, coverHeight)
                gradient.layoutParams = FrameLayout.LayoutParams(
                        MATCH_PARENT, coverHeight / 2, Gravity.BOTTOM)
            }
            LibraryGridHolder(view, adapter)
        } else {
            LibraryListHolder(view, adapter)
        }
    }

    override fun bindViewHolder(adapter: FlexibleAdapter<IFlexible<androidx.recyclerview.widget.RecyclerView.ViewHolder>>,
                                holder: LibraryHolder,
                                position: Int,
                                payloads: List<Any?>?) {

        holder.onSetValues(this)
    }

    /**
     * Returns true if this item is draggable.
     */
    override fun isDraggable(): Boolean {
        return true
    }

    /**
     * Filters a manga depending on a query.
     *
     * @param constraint the query to apply.
     * @return true if the manga should be included, false otherwise.
     */
    override fun filter(constraint: String): Boolean {
        return manga.title.contains(constraint, true) ||
            (manga.author?.contains(constraint, true) ?: false) ||
            if (constraint.contains(" ") || constraint.contains("\"")) {
                val genres = manga.genre?.split(", ")?.map {
                    it.drop(it.indexOfFirst{it==':'}+1).toLowerCase().trim() //tachiEH tag namespaces
                }
                var clean_constraint = ""
                var ignorespace = false
                for (i in constraint.trim().toLowerCase()) {
                    if (i==' ') {
                       if (!ignorespace) {
                           clean_constraint = clean_constraint + ","
                       } else {
                           clean_constraint = clean_constraint + " "
                       }
                    } else if (i=='"') {
                        ignorespace = !ignorespace
                    } else {
                        clean_constraint = clean_constraint + Character.toString(i)
                    }
                }
		clean_constraint.split(",").all { containsGenre(it.trim(), genres) }
            }
            else containsGenre(constraint, manga.genre?.split(", ")?.map {
                it.drop(it.indexOfFirst{it==':'}+1).toLowerCase().trim() //tachiEH tag namespaces
            })
    }

    private fun containsGenre(tag: String, genres: List<String>?): Boolean {
        return if (tag.startsWith("-"))
            genres?.find {
                it.trim().toLowerCase() == tag.substringAfter("-").toLowerCase()
            } == null
        else
            genres?.find {
                it.trim().toLowerCase() == tag.toLowerCase()
            } != null
    }

    override fun equals(other: Any?): Boolean {
        if (other is LibraryItem) {
            return manga.id == other.manga.id
        }
        return false
    }

    override fun hashCode(): Int {
        return manga.id!!.hashCode()
    }
}
