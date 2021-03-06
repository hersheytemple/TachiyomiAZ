package eu.kanade.tachiyomi.ui.catalogue.browse

import android.content.res.Configuration
import android.os.Bundle
import android.view.*
import androidx.appcompat.widget.SearchView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.MaterialDialog
import com.elvishew.xlog.XLog
import com.f2prateek.rx.preferences.Preference
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding.support.v7.widget.queryTextChangeEvents
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.base.controller.SecondaryDrawerController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.catalogue.CatalogueController
import eu.kanade.tachiyomi.ui.library.ChangeMangaCategoriesDialog
import eu.kanade.tachiyomi.ui.manga.MangaController
import eu.kanade.tachiyomi.ui.manga.info.MangaWebViewController
import eu.kanade.tachiyomi.util.*
import eu.kanade.tachiyomi.widget.AutofitRecyclerView
import exh.EXHSavedSearch
import kotlinx.android.synthetic.main.catalogue_controller.*
import kotlinx.android.synthetic.main.main_activity.*
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.subscriptions.Subscriptions
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

/**
 * Controller to manage the catalogues available in the app.
 */
open class BrowseCatalogueController(bundle: Bundle) :
        NucleusController<BrowseCataloguePresenter>(bundle),
        SecondaryDrawerController,
        FlexibleAdapter.OnItemClickListener,
        FlexibleAdapter.OnItemLongClickListener,
        FlexibleAdapter.EndlessScrollListener,
        ChangeMangaCategoriesDialog.Listener {

    constructor(source: CatalogueSource,
                searchQuery: String? = null,
                smartSearchConfig: CatalogueController.SmartSearchConfig? = null) : this(Bundle().apply {
        putLong(SOURCE_ID_KEY, source.id)

        if(searchQuery != null)
            putString(SEARCH_QUERY_KEY, searchQuery)

        if (smartSearchConfig != null)
            putParcelable(SMART_SEARCH_CONFIG_KEY, smartSearchConfig)
    })

    /**
     * Preferences helper.
     */
    private val preferences: PreferencesHelper by injectLazy()

    /**
     * Adapter containing the list of manga from the catalogue.
     */
    private var adapter: FlexibleAdapter<IFlexible<*>>? = null

    /**
     * Snackbar containing an error message when a request fails.
     */
    private var snack: Snackbar? = null

    /**
     * Navigation view containing filter items.
     */
    private var navView: CatalogueNavigationView? = null

    /**
     * Recycler view with the list of results.
     */
    private var recycler: androidx.recyclerview.widget.RecyclerView? = null

    /**
     * Subscription for the search view.
     */
    private var searchViewSubscription: Subscription? = null

    /**
     * Subscription for the number of manga per row.
     */
    private var numColumnsSubscription: Subscription? = null

    /**
     * Endless loading item.
     */
    private var progressItem: ProgressItem? = null

    init {
        setHasOptionsMenu(true)
    }

    override fun getTitle(): String? {
        return presenter.source.name
    }

    override fun createPresenter(): BrowseCataloguePresenter {
        return BrowseCataloguePresenter(args.getLong(SOURCE_ID_KEY),
                args.getString(SEARCH_QUERY_KEY))
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        return inflater.inflate(R.layout.catalogue_controller, container, false)
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        // Initialize adapter, scroll listener and recycler views
        adapter = FlexibleAdapter(null, this)
        setupRecycler(view)

        navView?.setFilters(presenter.filterItems)

        progress?.visible()
    }

    override fun onDestroyView(view: View) {
        numColumnsSubscription?.unsubscribe()
        numColumnsSubscription = null
        searchViewSubscription?.unsubscribe()
        searchViewSubscription = null
        adapter = null
        snack = null
        recycler = null
        super.onDestroyView(view)
    }

    override fun createSecondaryDrawer(drawer: androidx.drawerlayout.widget.DrawerLayout): ViewGroup? {
        // Inflate and prepare drawer
        val navView = drawer.inflate(R.layout.catalogue_drawer) as CatalogueNavigationView //TODO whatever this is
        this.navView = navView
        navView.setFilters(presenter.filterItems)

        drawer.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.END)

        // EXH -->
        navView.setSavedSearches(presenter.loadSearches())
        navView.onSaveClicked = {
            MaterialDialog.Builder(navView.context)
                    .title("Save current search query?")
                    .input("My search name", "") { _, searchName ->
                        val oldSavedSearches = presenter.loadSearches()
                        if(searchName.isNotBlank()
                                && oldSavedSearches.size < CatalogueNavigationView.MAX_SAVED_SEARCHES) {
                            val newSearches = oldSavedSearches + EXHSavedSearch(
                                    searchName.toString().trim(),
                                    presenter.query,
                                    presenter.sourceFilters
                            )
                            presenter.saveSearches(newSearches)
                            navView.setSavedSearches(newSearches)
                        }
                    }
                    .positiveText("Save")
                    .negativeText("Cancel")
                    .cancelable(true)
                    .canceledOnTouchOutside(true)
                    .show()
        }

        navView.onSavedSearchClicked = cb@{ indexToSearch ->
            val savedSearches = presenter.loadSearches()

            val search = savedSearches.getOrNull(indexToSearch)

            if(search == null) {
                MaterialDialog.Builder(navView.context)
                        .title("Failed to load saved searches!")
                        .content("An error occurred while loading your saved searches.")
                        .cancelable(true)
                        .canceledOnTouchOutside(true)
                        .show()
                return@cb
            }

            presenter.sourceFilters = FilterList(search.filterList)
            navView.setFilters(presenter.filterItems)
            val allDefault = presenter.sourceFilters == presenter.source.getFilterList()

            showProgressBar()
            adapter?.clear()
            drawer.closeDrawer(GravityCompat.END)
            presenter.restartPager(search.query, if (allDefault) FilterList() else presenter.sourceFilters)
            activity?.invalidateOptionsMenu()
        }

        navView.onSavedSearchDeleteClicked = cb@{ indexToDelete, name ->
            val savedSearches = presenter.loadSearches()

            val search = savedSearches.getOrNull(indexToDelete)

            if(search == null || search.name != name) {
                MaterialDialog.Builder(navView.context)
                        .title("Failed to delete saved search!")
                        .content("An error occurred while deleting the search.")
                        .cancelable(true)
                        .canceledOnTouchOutside(true)
                        .show()
                return@cb
            }

            MaterialDialog.Builder(navView.context)
                    .title("Delete saved search query?")
                    .content("Are you sure you wish to delete your saved search query: '${search.name}'?")
                    .positiveText("Cancel")
                    .negativeText("Confirm")
                    .onNegative { _, _ ->
                        val newSearches = savedSearches.filterIndexed { index, _ ->
                            index != indexToDelete
                        }
                        presenter.saveSearches(newSearches)
                        navView.setSavedSearches(newSearches)
                    }
                    .cancelable(true)
                    .canceledOnTouchOutside(true)
                    .show()
        }
        // EXH <--

        navView.onSearchClicked = {
            val allDefault = presenter.sourceFilters == presenter.source.getFilterList()
            showProgressBar()
            adapter?.clear()
            drawer.closeDrawer(GravityCompat.END)
            presenter.setSourceFilter(if (allDefault) FilterList() else presenter.sourceFilters)
        }

        navView.onResetClicked = {
            presenter.appliedFilters = FilterList()
            val newFilters = presenter.source.getFilterList()
            presenter.sourceFilters = newFilters
            navView.setFilters(presenter.filterItems)
        }
        return navView as ViewGroup //TODO fix this bullshit
    }

    override fun cleanupSecondaryDrawer(drawer: androidx.drawerlayout.widget.DrawerLayout) {
        navView = null
    }

    private fun setupRecycler(view: View) {
        numColumnsSubscription?.unsubscribe()

        var oldPosition = androidx.recyclerview.widget.RecyclerView.NO_POSITION
        val oldRecycler = catalogue_view?.getChildAt(1)
        if (oldRecycler is androidx.recyclerview.widget.RecyclerView) {
            oldPosition = (oldRecycler.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition()
            oldRecycler.adapter = null

            catalogue_view?.removeView(oldRecycler)
        }

        val recycler = if (presenter.isListMode) {
            androidx.recyclerview.widget.RecyclerView(view.context).apply {
                id = R.id.recycler
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
                layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                addItemDecoration(androidx.recyclerview.widget.DividerItemDecoration(context, androidx.recyclerview.widget.DividerItemDecoration.VERTICAL))
            }
        } else {
            (catalogue_view.inflate(R.layout.catalogue_recycler_autofit) as AutofitRecyclerView).apply {
                numColumnsSubscription = getColumnsPreferenceForCurrentOrientation().asObservable()
                        .doOnNext { spanCount = it }
                        .skip(1)
                        // Set again the adapter to recalculate the covers height
                        .subscribe { adapter = this@BrowseCatalogueController.adapter }

                (layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        return when (adapter?.getItemViewType(position)) {
                            R.layout.catalogue_grid_item, null -> 1
                            else -> spanCount
                        }
                    }
                }
            }
        }
        recycler.setHasFixedSize(true)
        recycler.adapter = adapter

        catalogue_view.addView(recycler, 1)

        if (oldPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
            recycler.layoutManager?.scrollToPosition(oldPosition)
        }
        this.recycler = recycler
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.catalogue_list, menu)

        // Initialize search menu
        menu.findItem(R.id.action_search).apply {
            val searchView = actionView as SearchView

            val query = presenter.query
            if (!query.isBlank()) {
                expandActionView()
                searchView.setQuery(query, true)
                searchView.clearFocus()
            }

            val searchEventsObservable = searchView.queryTextChangeEvents()
                    .skip(1)
                    .share()
            val writingObservable = searchEventsObservable
                    .filter { !it.isSubmitted }
                    .debounce(1250, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
            val submitObservable = searchEventsObservable
                    .filter { it.isSubmitted }

            searchViewSubscription?.unsubscribe()
            searchViewSubscription = Observable.merge(writingObservable, submitObservable)
                    .map { it.queryText().toString() }
                    .distinctUntilChanged()
                    .subscribeUntilDestroy { searchWithQuery(it) }

            untilDestroySubscriptions.add(
                    Subscriptions.create { if (isActionViewExpanded) collapseActionView() })
        }

        // Setup filters button
        menu.findItem(R.id.action_set_filter).apply {
            icon.mutate()
            if (presenter.sourceFilters.isEmpty()) {
//                isEnabled = false [EXH]
                icon.alpha = 128
            } else {
//                isEnabled = true [EXH]
                icon.alpha = 255
            }
        }

        // Show next display mode
        menu.findItem(R.id.action_display_mode).apply {
            val icon = if (presenter.isListMode)
                R.drawable.ic_view_module_white_24dp
            else
                R.drawable.ic_view_list_white_24dp
            setIcon(icon)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        val isHttpSource = presenter.source is HttpSource
        menu.findItem(R.id.action_open_in_browser).isVisible = isHttpSource
        menu.findItem(R.id.action_open_in_web_view).isVisible = isHttpSource
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_display_mode -> swapDisplayMode()
            R.id.action_set_filter -> navView?.let { activity?.drawer?.openDrawer(GravityCompat.END) }
            R.id.action_open_in_browser -> openInBrowser()
            R.id.action_open_in_web_view -> openInWebView()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun openInBrowser() {
        val source = presenter.source as? HttpSource ?: return

        activity?.openInBrowser(source.baseUrl)
    }

    private fun openInWebView() {
        val source = presenter.source as? HttpSource ?: return

        router.pushController(MangaWebViewController(source.id, source.baseUrl)
                .withFadeTransaction())
    }

    /**
     * Restarts the request with a new query.
     *
     * @param newQuery the new query.
     */
    private fun searchWithQuery(newQuery: String) {
        // If text didn't change, do nothing
        if (presenter.query == newQuery)
            return

        // FIXME dirty fix to restore the toolbar buttons after closing search mode.
        if (newQuery == "") {
            activity?.invalidateOptionsMenu()
        }

        showProgressBar()
        adapter?.clear()

        presenter.restartPager(newQuery)
    }

    /**
     * Called from the presenter when the network request is received.
     *
     * @param page the current page.
     * @param mangas the list of manga of the page.
     */
    fun onAddPage(page: Int, mangas: List<CatalogueItem>) {
        val adapter = adapter ?: return
        hideProgressBar()
        if (page == 1) {
            adapter.clear()
            resetProgressItem()
        }
        adapter.onLoadMoreComplete(mangas)
    }

    /**
     * Called from the presenter when the network request fails.
     *
     * @param error the error received.
     */
    fun onAddPageError(error: Throwable) {
        XLog.w("> Failed to load next catalogue page!", error)
        XLog.w("> (source.id: %s, source.name: %s)",
                presenter.source.id,
                presenter.source.name)

        val adapter = adapter ?: return
        adapter.onLoadMoreComplete(null)
        hideProgressBar()

        snack?.dismiss()

        if (catalogue_view != null) {
            val message = if (error is NoResultsException) catalogue_view.context.getString(R.string.no_results_found) else (error.message ?: "")

            snack = catalogue_view.snack(message, Snackbar.LENGTH_INDEFINITE) {
                setAction(R.string.action_retry) {
                    // If not the first page, show bottom progress bar.
                    if (adapter.mainItemCount > 0) {
                        val item = progressItem ?: return@setAction
                        adapter.addScrollableFooterWithDelay(item, 0, true)
                    } else {
                        showProgressBar()
                    }
                    presenter.requestNext()
                }
            }
        }
    }

    /**
     * Sets a new progress item and reenables the scroll listener.
     */
    private fun resetProgressItem() {
        progressItem = ProgressItem()
        adapter?.endlessTargetCount = 0
        adapter?.setEndlessScrollListener(this, progressItem!!)
    }

    /**
     * Called by the adapter when scrolled near the bottom.
     */
    override fun onLoadMore(lastPosition: Int, currentPage: Int) {
        if (presenter.hasNextPage()) {
            presenter.requestNext()
        } else {
            adapter?.onLoadMoreComplete(null)
            adapter?.endlessTargetCount = 1
        }
    }

    override fun noMoreLoad(newItemsSize: Int) {
    }

    /**
     * Called from the presenter when a manga is initialized.
     *
     * @param manga the manga initialized
     */
    fun onMangaInitialized(manga: Manga) {
        getHolder(manga)?.setImage(manga)
    }

    /**
     * Swaps the current display mode.
     */
    fun swapDisplayMode() {
        val view = view ?: return
        val adapter = adapter ?: return

        presenter.swapDisplayMode()
        val isListMode = presenter.isListMode
        activity?.invalidateOptionsMenu()
        setupRecycler(view)
        if (!isListMode || !view.context.connectivityManager.isActiveNetworkMetered) {
            // Initialize mangas if going to grid view or if over wifi when going to list view
            val mangas = (0 until adapter.itemCount).mapNotNull {
                (adapter.getItem(it) as? CatalogueItem)?.manga
            }
            presenter.initializeMangas(mangas)
        }
    }

    /**
     * Returns a preference for the number of manga per row based on the current orientation.
     *
     * @return the preference.
     */
    fun getColumnsPreferenceForCurrentOrientation(): Preference<Int> {
        return if (resources?.configuration?.orientation == Configuration.ORIENTATION_PORTRAIT)
            preferences.portraitColumns()
        else
            preferences.landscapeColumns()
    }

    /**
     * Returns the view holder for the given manga.
     *
     * @param manga the manga to find.
     * @return the holder of the manga or null if it's not bound.
     */
    private fun getHolder(manga: Manga): CatalogueHolder? {
        val adapter = adapter ?: return null

        adapter.allBoundViewHolders.forEach { holder ->
            val item = adapter.getItem(holder.adapterPosition) as? CatalogueItem
            if (item != null && item.manga.id!! == manga.id!!) {
                return holder as CatalogueHolder
            }
        }

        return null
    }

    /**
     * Shows the progress bar.
     */
    private fun showProgressBar() {
        progress?.visible()
        snack?.dismiss()
        snack = null
    }

    /**
     * Hides active progress bars.
     */
    private fun hideProgressBar() {
        progress?.gone()
    }

    /**
     * Called when a manga is clicked.
     *
     * @param position the position of the element clicked.
     * @return true if the item should be selected, false otherwise.
     */
    override fun onItemClick(view: View, position: Int): Boolean {
        val item = adapter?.getItem(position) as? CatalogueItem ?: return false
        router.pushController(MangaController(item.manga,
                true,
                args.getParcelable(SMART_SEARCH_CONFIG_KEY)).withFadeTransaction())

        return false
    }

    /**
     * Called when a manga is long clicked.
     *
     * Adds the manga to the default category if none is set it shows a list of categories for the user to put the manga
     * in, the list consists of the default category plus the user's categories. The default category is preselected on
     * new manga, and on already favorited manga the manga's categories are preselected.
     *
     * @param position the position of the element clicked.
     */
    override fun onItemLongClick(position: Int) {
        val activity = activity ?: return
        val manga = (adapter?.getItem(position) as? CatalogueItem?)?.manga ?: return
        if (manga.favorite) {
            MaterialDialog.Builder(activity)
                    .items(activity.getString(R.string.remove_from_library))
                    .itemsCallback { _, _, which, _ ->
                        when (which) {
                            0 -> {
                                presenter.changeMangaFavorite(manga)
                                adapter?.notifyItemChanged(position)
                                activity?.toast(activity?.getString(R.string.manga_removed_library))
                            }
                        }
                    }.show()
        } else {
            presenter.changeMangaFavorite(manga)
            adapter?.notifyItemChanged(position)

            val categories = presenter.getCategories()
            val defaultCategoryId = preferences.defaultCategory()
            val defaultCategory = categories.find { it.id == defaultCategoryId }
            when {
                defaultCategory != null -> presenter.moveMangaToCategory(manga, defaultCategory)
                defaultCategoryId == 0 || categories.isEmpty() -> // 'Default' or no category
                    presenter.moveMangaToCategory(manga, null)
                else -> {
                    val ids = presenter.getMangaCategoryIds(manga)
                    val preselected = ids.mapNotNull { id ->
                        categories.indexOfFirst { it.id == id }.takeIf { it != -1 }
                    }.toTypedArray()

                    ChangeMangaCategoriesDialog(this, listOf(manga), categories, preselected)
                            .showDialog(router)
                }
            }
            activity?.toast(activity?.getString(R.string.manga_added_library))
        }

    }

    /**
     * Update manga to use selected categories.
     *
     * @param mangas The list of manga to move to categories.
     * @param categories The list of categories where manga will be placed.
     */
    override fun updateCategoriesForMangas(mangas: List<Manga>, categories: List<Category>) {
        val manga = mangas.firstOrNull() ?: return
        presenter.updateMangaCategories(manga, categories)
    }

    protected companion object {
        const val SOURCE_ID_KEY = "sourceId"
        const val SEARCH_QUERY_KEY = "searchQuery"
        // EXH -->
        const val SMART_SEARCH_CONFIG_KEY = "smartSearchConfig"
        // EXH <--
    }

}
