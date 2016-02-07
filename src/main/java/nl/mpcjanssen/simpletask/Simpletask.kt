/**
 * @author Todo.txt contributors @yahoogroups.com>
 * *
 * @author Mark Janssen
 * *
 * @license http://www.gnu.org/licenses/gpl.html
 * *
 * @copyright 2009-2012 Todo.txt contributors (http://todotxt.com)
 * *
 * @copyright 2013- Mark Janssen
 * *
 * @copyright 2015 Vojtech Kral
 */

package nl.mpcjanssen.simpletask

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.SearchManager
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.support.design.widget.FloatingActionButton
import android.support.v4.content.ContextCompat
import android.support.v4.content.LocalBroadcastManager
import android.support.v4.view.GravityCompat
import android.support.v4.view.MenuItemCompat
import android.support.v4.view.MenuItemCompat.OnActionExpandListener
import android.support.v4.widget.DrawerLayout
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.app.AlertDialog
import android.support.v7.widget.Toolbar
import android.text.SpannableString
import android.text.TextUtils
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import hirondelle.date4j.DateTime
import nl.mpcjanssen.simpletask.adapters.DrawerAdapter
import nl.mpcjanssen.simpletask.remote.FileStoreInterface
import nl.mpcjanssen.simpletask.task.Priority
import nl.mpcjanssen.simpletask.task.TToken
import nl.mpcjanssen.simpletask.task.Task
import nl.mpcjanssen.simpletask.task.TodoList
import nl.mpcjanssen.simpletask.task.TodoListItem
import nl.mpcjanssen.simpletask.util.InputDialogListener
import nl.mpcjanssen.simpletask.util.*

import java.io.File
import java.util.*


class Simpletask : ThemedActivity(), AbsListView.OnScrollListener, AdapterView.OnItemLongClickListener {

    internal var options_menu: Menu? = null
    internal lateinit var m_app: TodoApplication
    internal var mFilter: ActiveFilter? = null
    internal var m_adapter: TaskAdapter? = null
    private var m_broadcastReceiver: BroadcastReceiver? = null
    private var localBroadcastManager: LocalBroadcastManager? = null

    // Drawer vars
    private var m_leftDrawerList: ListView? = null
    private var m_rightDrawerList: ListView? = null
    private var m_drawerLayout: DrawerLayout? = null
    private var m_drawerToggle: ActionBarDrawerToggle? = null
    private var m_savedInstanceState: Bundle? = null
    internal var m_scrollPosition = 0
    private var mOverlayDialog: Dialog? = null
    private var mIgnoreScrollEvents = false
    private var log: Logger? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = Logger
        log!!.info(TAG, "onCreate")
        m_app = application as TodoApplication
        m_savedInstanceState = savedInstanceState
        val intentFilter = IntentFilter()
        intentFilter.addAction(Constants.BROADCAST_ACTION_ARCHIVE)
        intentFilter.addAction(Constants.BROADCAST_ACTION_LOGOUT)
        intentFilter.addAction(Constants.BROADCAST_UPDATE_UI)
        intentFilter.addAction(Constants.BROADCAST_SYNC_START)
        intentFilter.addAction(Constants.BROADCAST_SYNC_DONE)
        intentFilter.addAction(Constants.BROADCAST_UPDATE_PENDING_CHANGES)
        intentFilter.addAction(Constants.BROADCAST_HIGHLIGHT_SELECTION)



        localBroadcastManager = m_app.localBroadCastManager

        m_broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Constants.BROADCAST_ACTION_ARCHIVE) {
                    archiveTasks(null)
                } else if (intent.action == Constants.BROADCAST_ACTION_LOGOUT) {
                    log!!.info(TAG, "Logging out from Dropbox")
                    fileStore.logout()
                    val i = Intent(context, LoginScreen::class.java)
                    startActivity(i)
                    finish()
                } else if (intent.action == Constants.BROADCAST_UPDATE_UI) {
                    log!!.info(TAG, "Updating UI because of broadcast")
                    if (m_adapter == null) {
                        return
                    }
                    m_adapter!!.setFilteredTasks()
                    updateDrawers()
                } else if (intent.action == Constants.BROADCAST_SYNC_START) {
                    mOverlayDialog = showLoadingOverlay(this@Simpletask, mOverlayDialog, true)
                } else if (intent.action == Constants.BROADCAST_SYNC_DONE) {
                    mOverlayDialog = showLoadingOverlay(this@Simpletask, mOverlayDialog, false)
                } else if (intent.action == Constants.BROADCAST_UPDATE_PENDING_CHANGES) {
                    updatePendingChanges()
                } else if (intent.action == Constants.BROADCAST_HIGHLIGHT_SELECTION) {
                    handleIntent()
                }
            }
        }
        localBroadcastManager!!.registerReceiver(m_broadcastReceiver, intentFilter)


        // Set the proper theme
        setTheme(m_app.activeTheme)
        if (m_app.hasLandscapeDrawers()) {
            setContentView(R.layout.main_landscape)
        } else {
            setContentView(R.layout.main)
        }

        // Replace drawables if the theme is dark
        if (m_app.isDarkTheme) {
            val actionBarClear = findViewById(R.id.actionbar_clear) as ImageView?
            actionBarClear?.setImageResource(R.drawable.cancel)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_SHARE_PARTS -> if (resultCode != Activity.RESULT_CANCELED) {
                val flags = resultCode - Activity.RESULT_FIRST_USER
                shareTodoList(flags)
            }
            REQUEST_PREFERENCES -> if (resultCode == Preferences.RESULT_RECREATE_ACTIVITY) {
                val i = Intent(applicationContext, Simpletask::class.java)
                i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                finish()
                m_app.reloadTheme()
                m_app.startActivity(i)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_PERMISSION -> m_app.switchTodoFile(m_app.todoFileName, false)
        }
    }

    private fun showHelp() {
        val i = Intent(this, HelpScreen::class.java)
        startActivity(i)
    }

    override fun onSearchRequested(): Boolean {
        if (options_menu == null) {
            return false
        }
        val searchMenuItem = options_menu!!.findItem(R.id.search)
        MenuItemCompat.expandActionView(searchMenuItem)

        return true
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Sync the toggle state after onRestoreInstanceState has occurred.
        if (m_drawerToggle != null) {
            m_drawerToggle!!.syncState()
        }
    }

    private fun selectedTasksAsString(): String {
        val result = ArrayList<String>()
        for (t in todoList.selectedTasks) {
            result.add(t.task.inFileFormat())
        }
        return join(result, "\n")
    }

    private fun selectAllTasks() {
        val selectedTasks = ArrayList<TodoListItem>()
        for (vline in m_adapter!!.visibleLines) {
            // Only check tasks that are not checked yet
            // and skip headers
            // This prevents double counting in the CAB title
            if (!vline.header) {
                selectedTasks.add(vline.task!!)
            }
        }
        val tl = todoList
        tl.clearSelection()
        todoList.selectTodoItems(selectedTasks)
        handleIntent()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (m_drawerToggle != null) {
            m_drawerToggle!!.onConfigurationChanged(newConfig)
        }
    }


    private fun handleIntent() {
        if (!m_app.isAuthenticated) {
            log!!.info(TAG, "handleIntent: not authenticated")
            startLogin()
            return
        }
        // Check if we have SDCard permision for cloudless
        if (!m_app.fileStore.getWritePermission(this, REQUEST_PERMISSION)) {
            return
        }

        mFilter = ActiveFilter()

        m_leftDrawerList = findViewById(R.id.left_drawer) as ListView
        m_rightDrawerList = findViewById(R.id.right_drawer_list) as ListView

        m_drawerLayout = findViewById(R.id.drawer_layout) as DrawerLayout

        // Set the list's click listener
        m_leftDrawerList!!.onItemClickListener = DrawerItemClickListener()

        if (m_drawerLayout != null) {
            m_drawerToggle = object : ActionBarDrawerToggle(this, /* host Activity */
                    m_drawerLayout, /* DrawerLayout object */
                    R.string.changelist, /* "open drawer" description */
                    R.string.app_label /* "close drawer" description */) {

                /**
                 * Called when a drawer has settled in a completely closed
                 * state.
                 */
                override fun onDrawerClosed(view: View?) {
                    // setTitle(R.string.app_label);
                }

                /** Called when a drawer has settled in a completely open state.  */
                override fun onDrawerOpened(drawerView: View?) {
                    // setTitle(R.string.changelist);
                }
            }

            // Set the drawer toggle as the DrawerListener
            m_drawerLayout!!.setDrawerListener(m_drawerToggle)
            val actionBar = supportActionBar
            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(true)
                actionBar.setHomeButtonEnabled(true)
                m_drawerToggle!!.isDrawerIndicatorEnabled = true
            }
            m_drawerToggle!!.syncState()
        }

        // Show search or filter results
        val intent = intent
        if (Constants.INTENT_START_FILTER == intent.action) {
            mFilter!!.initFromIntent(intent)
            log!!.info(TAG, "handleIntent: launched with filter" + mFilter!!)
            val extras = intent.extras
            if (extras != null) {
                for (key in extras.keySet()) {
                    val value = extras.get(key)
                    if (value != null) {
                        log!!.debug(TAG, "%s %s (%s)".format(key, value.toString(), value.javaClass.name))
                    } else {
                        log!!.debug(TAG, "%s %s)".format(key, "<null>"))
                    }

                }

            }
            log!!.info(TAG, "handleIntent: saving filter in prefs")
            mFilter!!.saveInPrefs(TodoApplication.prefs)
        } else {
            // Set previous filters and sort
            log!!.info(TAG, "handleIntent: from m_prefs state")
            mFilter!!.initFromPrefs(TodoApplication.prefs)
        }

        // Initialize Adapter
        if (m_adapter == null) {
            m_adapter = TaskAdapter(layoutInflater)
        }
        m_adapter!!.setFilteredTasks()

        listView!!.adapter = this.m_adapter

        val lv = listView
        if (lv == null) {
            return
        }
        lv.isTextFilterEnabled = true
        lv.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        lv.isClickable = true
        lv.isLongClickable = true
        lv.onItemLongClickListener = this


        lv.onItemClickListener = AdapterView.OnItemClickListener { parent, view, position, id ->
            val links = ArrayList<String>()
            val actions = ArrayList<String>()
            lv.setItemChecked(position, !lv.isItemChecked(position))
            if (todoList.selectedTasks.size > 0) {
                onItemLongClick(parent, view, position, id)
                return@OnItemClickListener
            }
            val item = getTaskAt(position)
            if (item != null) {
                val t = item.task
                for (link in t.links) {
                    actions.add(ACTION_LINK)
                    links.add(link)
                }
                for (number in t.phoneNumbers) {
                    actions.add(ACTION_PHONE)
                    links.add(number)
                    actions.add(ACTION_SMS)
                    links.add(number)
                }
                for (mail in t.mailAddresses) {
                    actions.add(ACTION_MAIL)
                    links.add(mail)
                }
            }
            if (links.size == 0) {
                onItemLongClick(parent, view, position, id)
            } else {
                // Decorate the links array
                val titles = ArrayList<String>()
                for (i in links.indices) {
                    when (actions[i]) {
                        ACTION_SMS -> titles.add(i, "SMS: " + links[i])
                        ACTION_PHONE -> titles.add(i, "Call: " + links[i])
                        else -> titles.add(i, links[i])
                    }
                }
                val build = AlertDialog.Builder(this@Simpletask)
                build.setTitle(R.string.task_action)
                val titleArray = titles.toArray<String>(arrayOfNulls<String>(titles.size))
                build.setItems(titleArray) { dialog, which ->
                    val intent: Intent
                    val url = links[which]
                    log!!.info(TAG, "" + actions[which] + ": " + url)
                    when (actions[which]) {
                        ACTION_LINK -> if (url.startsWith("todo://")) {
                            val todoFolder = m_app.todoFile.parentFile
                            val newName = File(todoFolder, url.substring(7))
                            m_app.switchTodoFile(newName.absolutePath, true)
                        } else {
                            intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            startActivity(intent)
                        }
                        ACTION_PHONE -> {
                            intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(url)))
                            startActivity(intent)
                        }
                        ACTION_SMS -> {
                            intent = Intent(Intent.ACTION_VIEW, Uri.parse("sms:" + Uri.encode(url)))
                            startActivity(intent)
                        }
                        ACTION_MAIL -> {
                            intent = Intent(Intent.ACTION_SEND, Uri.parse(url))
                            intent.putExtra(android.content.Intent.EXTRA_EMAIL,
                                    arrayOf(url))
                            intent.setType("text/plain")
                            startActivity(intent)
                        }
                    }
                }
                build.create().show()
            }
        }

        mIgnoreScrollEvents = true
        // Setting a scroll listener reset the scroll
        lv.setOnScrollListener(this)
        mIgnoreScrollEvents = false
        if (m_savedInstanceState != null) {
            m_scrollPosition = m_savedInstanceState!!.getInt("position")
        }

        lv.isFastScrollEnabled = m_app.useFastScroll()


        // If we were started from the widget, select the pushed task
        // and scroll to its position
        if (intent.hasExtra(Constants.INTENT_SELECTED_TASK)) {
            val line = intent.getStringExtra(Constants.INTENT_SELECTED_TASK)
            intent.removeExtra(Constants.INTENT_SELECTED_TASK)
            setIntent(intent)
            if (line != null) {
                todoList.clearSelection()
                val tasks = ArrayList<Task>()
                tasks.add(Task(line))
                todoList.selectTasks(tasks, localBroadcastManager)
            }
        }
        val selection = todoList.selectedTasks
        if (selection.size > 0) {
            val selectedTask = selection[0]
            m_scrollPosition = m_adapter!!.getPosition(selectedTask)
            openSelectionMode()
        } else {
            closeSelectionMode()
        }
        // Check the selected items in the listview
        setSelectedTasks(selection)
        val fab = findViewById(R.id.fab) as FloatingActionButton
        lv.setSelectionFromTop(m_scrollPosition, 0)
        fab.setOnClickListener { startAddTaskActivity() }

        updateDrawers()
        mOverlayDialog = showLoadingOverlay(this, mOverlayDialog, m_app.isLoading)
        updatePendingChanges()


    }

    private fun updatePendingChanges() {
        // Show pending changes indicator
        val pendingChanges = findViewById(R.id.pendingchanges)
        if (pendingChanges != null) {
            if (fileStore.changesPending()) {
                pendingChanges.visibility = View.VISIBLE
            } else {
                pendingChanges.visibility = View.GONE
            }
        }
    }

    private fun setSelectedTasks(items: List<TodoListItem>?) {
        if (items == null) return
        val lv = listView
        if (lv == null) return
        for (t in items) {
            val position = m_adapter!!.getPosition(t)
            if (position != -1) {
                lv.setItemChecked(position, true)
                lv.setSelection(position)
            }
        }
    }

    private fun updateFilterBar() {
        val lv = listView ?: return
        val index = lv.firstVisiblePosition
        val v = lv.getChildAt(0)
        val top = if (v == null) 0 else v.top
        lv.setSelectionFromTop(index, top)

        val actionbar = findViewById(R.id.actionbar) as LinearLayout
        val filterText = findViewById(R.id.filter_text) as TextView
        if (mFilter!!.hasFilter()) {
            actionbar.visibility = View.VISIBLE
        } else {
            actionbar.visibility = View.GONE
        }
        val count = if (m_adapter != null) m_adapter!!.countVisibleTodoItems else 0
        val total = todoList.size().toLong()

        filterText.text = mFilter!!.getTitle(
                count,
                total,
                getText(R.string.priority_prompt),
                m_app.tagTerm,
                m_app.listTerm,
                getText(R.string.search),
                getText(R.string.script),
                getText(R.string.title_filter_applied),
                getText(R.string.no_filter))
    }

    private fun startLogin() {
        val intent = Intent(this, LoginScreen::class.java)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        localBroadcastManager!!.unregisterReceiver(m_broadcastReceiver)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("position", listView!!.firstVisiblePosition)
    }

    override fun onResume() {
        super.onResume()
        handleIntent()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {

        this.options_menu = menu
        if (todoList.selectedTasks.size > 0) {
            openSelectionMode()
        } else {
            populateMainMenu(menu)
        }
        return super.onCreateOptionsMenu(menu)
    }

    private fun populateMainMenu(menu: Menu?) {

        if (menu == null) {
            log!!.warn(TAG, "Menu was null")
            return
        }
        menu.clear()
        val inflater = menuInflater
        inflater.inflate(R.menu.main, menu)

        if (!fileStore.supportsSync()) {
            val mItem = menu.findItem(R.id.sync)
            mItem.setVisible(false)
        }
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        val searchMenu = menu.findItem(R.id.search)

        val searchView = searchMenu.actionView as SearchView
        searchView.setSearchableInfo(searchManager.getSearchableInfo(componentName))

        searchView.setIconifiedByDefault(false)
        MenuItemCompat.setOnActionExpandListener(searchMenu, object : OnActionExpandListener {
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                // Do something when collapsed
                return true  // Return true to collapse action view
            }

            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                //get focus
                item.actionView.requestFocus()
                //get input method
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.toggleSoftInput(0, InputMethodManager.HIDE_NOT_ALWAYS)
                return true  // Return true to expand action view
            }
        })

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            var m_ignoreSearchChangeCallback: Boolean = false

            override fun onQueryTextSubmit(query: String): Boolean {
                // Stupid searchview code will call onQueryTextChange callback
                // When the actionView collapse and the textview is reset
                // ugly global hack around this
                m_ignoreSearchChangeCallback = true
                menu.findItem(R.id.search).collapseActionView()
                m_ignoreSearchChangeCallback = false
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                if (!m_ignoreSearchChangeCallback) {
                    if (mFilter == null) {
                        mFilter = ActiveFilter()
                    }
                    mFilter!!.search = newText
                    mFilter!!.saveInPrefs(TodoApplication.prefs)
                    if (m_adapter != null) {
                        m_adapter!!.setFilteredTasks()
                    }
                }
                return true
            }
        })
    }

    private fun getTaskAt(pos: Int): TodoListItem? {
        if (pos < m_adapter!!.count) {
            return m_adapter!!.getItem(pos)
        }
        return null
    }

    private fun shareTodoList(format: Int) {
        val text = StringBuilder()
        for (i in 0..m_adapter!!.count - 1 - 1) {
            val task = m_adapter!!.getItem(i)
            if (task != null) {
                text.append(task.task.showParts(format)).append("\n")
            }
        }
        shareText(this, text.toString())
    }


    private fun prioritizeTasks(tasks: List<TodoListItem>) {
        val strings = Priority.rangeInCode(Priority.NONE, Priority.Z)
        val prioArr = strings.toTypedArray()

        var prioIdx = 0
        if (tasks.size == 1) {
            prioIdx = strings.indexOf(tasks[0].task.priority.code)
        }
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.select_priority)
        builder.setSingleChoiceItems(prioArr, prioIdx, { dialog, which ->
            dialog.dismiss()
            val prio = Priority.toPriority(prioArr[which])
            todoList.prioritize(tasks, prio)
            todoList.notifyChanged(m_app.fileStore, m_app.todoFileName, m_app.eol, m_app, true)
            closeSelectionMode()
        })
        builder.show()

    }

    private fun completeTasks(task: TodoListItem) {
        val tasks = ArrayList<TodoListItem>()
        tasks.add(task)
        completeTasks(tasks)
    }

    private fun completeTasks(tasks: List<TodoListItem>) {
        for (t in tasks) {
            todoList.complete(t, m_app.hasKeepPrio(), m_app.hasAppendAtEnd())
        }
        if (m_app.isAutoArchive) {
            archiveTasks(null)
        }
        closeSelectionMode()
        todoList.notifyChanged(m_app.fileStore, m_app.todoFileName, m_app.eol, m_app, true)
    }

    private fun undoCompleteTasks(task: TodoListItem) {
        val tasks = ArrayList<TodoListItem>()
        tasks.add(task)
        undoCompleteTasks(tasks)
    }

    private fun undoCompleteTasks(tasks: List<TodoListItem>) {
        todoList.undoComplete(tasks)
        closeSelectionMode()
        todoList.notifyChanged(m_app.fileStore, m_app.todoFileName, m_app.eol, m_app, true)
    }

    private fun deferTasks(tasks: List<TodoListItem>, dateType: DateType) {
        var titleId = R.string.defer_due
        if (dateType === DateType.THRESHOLD) {
            titleId = R.string.defer_threshold
        }
        val d = createDeferDialog(this, titleId, true, object : InputDialogListener {
            override fun onClick(input: String) {
                if (input == "pick") {
                    val today = DateTime.today(TimeZone.getDefault())
                    val dialog = DatePickerDialog(this@Simpletask, DatePickerDialog.OnDateSetListener { datePicker, year, month, day ->
                        var month = month
                        month++
                        val date = DateTime.forDateOnly(year, month, day)
                        m_app.todoList.defer(date.format(Constants.DATE_FORMAT), tasks, dateType)
                        closeSelectionMode()
                        todoList.notifyChanged(m_app.fileStore, m_app.todoFileName, m_app.eol, m_app, true)
                    },
                            today.year!!,
                            today.month!! - 1,
                            today.day!!)
                    val showCalendar = m_app.showCalendar()

                    dialog.datePicker.calendarViewShown = showCalendar
                    dialog.datePicker.spinnersShown = !showCalendar
                    dialog.show()
                } else {

                    m_app.todoList.defer(input, tasks, dateType)
                    closeSelectionMode()
                    todoList.notifyChanged(m_app.fileStore, m_app.todoFileName, m_app.eol, m_app, true)

                }

            }
        })
        d.show()
    }

    private fun deleteTasks(tasks: List<TodoListItem>) {
        m_app.showConfirmationDialog(this, R.string.delete_task_message, DialogInterface.OnClickListener { dialogInterface, i ->
            for (t in tasks) {
                m_app.todoList.remove(t)
            }
            closeSelectionMode()
            todoList.notifyChanged(m_app.fileStore, m_app.todoFileName, m_app.eol, m_app, true)
        }, R.string.delete_task_title)
    }

    private fun archiveTasks(tasksToArchive: List<TodoListItem>?) {
        if (m_app.todoFileName == m_app.doneFileName) {
            showToastShort(this, "You have the done.txt file opened.")
            return
        }
        todoList.archive(fileStore, m_app.todoFileName, m_app.doneFileName, tasksToArchive, m_app.eol)
        closeSelectionMode()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (m_drawerToggle != null && m_drawerToggle!!.onOptionsItemSelected(item)) {
            return true
        }
        log!!.info(TAG, "onMenuItemSelected: " + item.itemId)
        when (item.itemId) {
            R.id.search -> {
            }
            R.id.preferences -> startPreferencesActivity()
            R.id.filter -> startFilterActivity()
            R.id.share -> startActivityForResult(Intent(baseContext, TaskDisplayActivity::class.java), REQUEST_SHARE_PARTS)
            R.id.help -> showHelp()
            R.id.sync -> fileStore.sync()
            R.id.archive -> m_app.showConfirmationDialog(this, R.string.delete_task_message, DialogInterface.OnClickListener { dialogInterface, i -> archiveTasks(null) }, R.string.archive_task_title)
            R.id.open_file -> m_app.browseForNewFile(this)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun startAddTaskActivity() {
        log!!.info(TAG, "Starting addTask activity")
        val intent = Intent(this, AddTask::class.java)
        mFilter!!.saveInIntent(intent)
        startActivity(intent)
    }

    private fun startPreferencesActivity() {
        val settingsActivity = Intent(baseContext,
                Preferences::class.java)
        startActivityForResult(settingsActivity, REQUEST_PREFERENCES)
    }

    /**
     * Handle clear filter click *
     */
    fun onClearClick(v: View) {
        clearFilter()
    }

    val savedFilter: ArrayList<ActiveFilter>
        get() {
            val saved_filters = ArrayList<ActiveFilter>()
            val saved_filter_ids = getSharedPreferences("filters", Context.MODE_PRIVATE)
            val filterIds = saved_filter_ids.getStringSet("ids", HashSet<String>())
            for (id in filterIds) {
                val filter_pref = getSharedPreferences(id, Context.MODE_PRIVATE)
                val filter = ActiveFilter()
                filter.initFromPrefs(filter_pref)
                filter.prefName = id
                saved_filters.add(filter)
            }
            return saved_filters
        }

    /**
     * Handle add filter click *
     */
    fun onAddFilterClick(v: View) {
        val alert = AlertDialog.Builder(this)

        alert.setTitle(R.string.save_filter)
        alert.setMessage(R.string.save_filter_message)

        // Set an EditText view to get user input
        val input = EditText(this)
        alert.setView(input)
        input.setText(mFilter!!.proposedName)

        alert.setPositiveButton("Ok") { dialog, whichButton ->
            val text = input.text
            val value: String
            if (text == null) {
                value = ""
            } else {
                value = text.toString()
            }
            if (value == "") {
                showToastShort(applicationContext, R.string.filter_name_empty)
            } else {
                val saved_filters = getSharedPreferences("filters", Context.MODE_PRIVATE)
                val newId = saved_filters.getInt("max_id", 1) + 1
                val filters = saved_filters.getStringSet("ids", HashSet<String>())
                filters.add("filter_" + newId)
                saved_filters.edit().putStringSet("ids", filters).putInt("max_id", newId).apply()
                val test_filter_prefs = getSharedPreferences("filter_" + newId, Context.MODE_PRIVATE)
                mFilter!!.name = value
                mFilter!!.saveInPrefs(test_filter_prefs)
                updateRightDrawer()
            }
        }

        alert.setNegativeButton("Cancel") { dialog, whichButton -> }
        alert.show()
    }

    override fun onBackPressed() {
        if (m_drawerLayout != null) {
            if (m_drawerLayout!!.isDrawerOpen(GravityCompat.START)) {
                m_drawerLayout!!.closeDrawer(GravityCompat.START)
                return
            }
            if (m_drawerLayout!!.isDrawerOpen(GravityCompat.END)) {
                m_drawerLayout!!.closeDrawer(GravityCompat.END)
                return
            }
        }
        if (todoList.selectedTasks.size > 0) {
            closeSelectionMode()
            return
        }
        if (m_app.backClearsFilter() && mFilter != null && mFilter!!.hasFilter()) {
            clearFilter()
            onNewIntent(intent)
            return
        }

        super.onBackPressed()
    }

    private fun closeSelectionMode() {
        todoList.clearSelection()
        listView!!.clearChoices()
        val toolbar = findViewById(R.id.toolbar) as Toolbar
        val fab = findViewById(R.id.fab) as FloatingActionButton
        fab.visibility = View.VISIBLE
        toolbar.visibility = View.GONE
        //getTodoList().clearSelectedTasks();
        populateMainMenu(options_menu)
        //updateDrawers();

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_SEARCH == intent.action) {
            val currentIntent = getIntent()
            currentIntent.putExtra(SearchManager.QUERY, intent.getStringExtra(SearchManager.QUERY))
            setIntent(currentIntent)
            if (options_menu == null) {
                return
            }
            options_menu!!.findItem(R.id.search).collapseActionView()

        } else if (CalendarContract.ACTION_HANDLE_CUSTOM_EVENT == intent.action) {
            // Uri uri = Uri.parse(intent.getStringExtra(CalendarContract.EXTRA_CUSTOM_APP_URI));
            log!!.warn(TAG, "Not implenented search")
        } else if (intent.extras != null) {
            // Only change intent if it actually contains a filter
            setIntent(intent)
        }
        log!!.info(TAG, "onNewIntent: " + intent)

    }

    internal fun clearFilter() {
        // Also clear the intent so we wont get the old filter after
        // switching back to app later fixes [1c5271ee2e]
        val intent = Intent()
        mFilter!!.clear()
        mFilter!!.saveInIntent(intent)
        mFilter!!.saveInPrefs(TodoApplication.prefs)
        setIntent(intent)
        closeSelectionMode()
        updateDrawers()
        m_adapter!!.setFilteredTasks()
    }

    private fun updateDrawers() {
        updateLeftDrawer()
        updateRightDrawer()
    }

    private fun updateRightDrawer() {
        val names = ArrayList<String>()
        val filters = savedFilter
        Collections.sort(filters) { f1, f2 -> f1.name!!.compareTo(f2.name!!, ignoreCase = true) }
        for (f in filters) {
            names.add(f.name!!)
        }
        m_rightDrawerList!!.adapter = ArrayAdapter(this, R.layout.drawer_list_item, names)
        m_rightDrawerList!!.choiceMode = AbsListView.CHOICE_MODE_NONE
        m_rightDrawerList!!.isLongClickable = true
        m_rightDrawerList!!.onItemClickListener = object : AdapterView.OnItemClickListener {
            override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                mFilter = filters[position]
                val intent = intent
                mFilter!!.saveInIntent(intent)
                setIntent(intent)
                mFilter!!.saveInPrefs(TodoApplication.prefs)
                m_adapter!!.setFilteredTasks()
                if (m_drawerLayout != null) {
                    m_drawerLayout!!.closeDrawer(GravityCompat.END)
                }
                updateDrawers()
            }
        }
        m_rightDrawerList!!.onItemLongClickListener = AdapterView.OnItemLongClickListener { parent, view, position, id ->
            val filter = filters[position]
            val prefsName = filter.prefName!!
            val popupMenu = PopupMenu(this@Simpletask, view)
            popupMenu.setOnMenuItemClickListener { item ->
                val menuid = item.itemId
                when (menuid) {
                    R.id.menu_saved_filter_delete -> deleteSavedFilter(prefsName)
                    R.id.menu_saved_filter_shortcut -> createFilterShortcut(filter)
                    R.id.menu_saved_filter_rename -> renameSavedFilter(prefsName)
                    R.id.menu_saved_filter_update -> updateSavedFilter(prefsName)
                    else -> {
                    }
                }
                true
            }
            val inflater = popupMenu.menuInflater
            inflater.inflate(R.menu.saved_filter, popupMenu.menu)
            popupMenu.show()
            true
        }
    }

    fun createFilterShortcut(filter: ActiveFilter) {
        val shortcut = Intent("com.android.launcher.action.INSTALL_SHORTCUT")
        val target = Intent(Constants.INTENT_START_FILTER)
        filter.saveInIntent(target)

        target.putExtra("name", filter.name)

        // Setup target intent for shortcut
        shortcut.putExtra(Intent.EXTRA_SHORTCUT_INTENT, target)

        // Set shortcut icon
        val iconRes = Intent.ShortcutIconResource.fromContext(this, R.drawable.ic_launcher)
        shortcut.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, iconRes)
        shortcut.putExtra(Intent.EXTRA_SHORTCUT_NAME, filter.name)
        sendBroadcast(shortcut)
    }

    private fun deleteSavedFilter(prefsName: String) {
        val saved_filters = getSharedPreferences("filters", Context.MODE_PRIVATE)
        val ids = HashSet<String>()
        ids.addAll(saved_filters.getStringSet("ids", HashSet<String>()))
        ids.remove(prefsName)
        saved_filters.edit().putStringSet("ids", ids).apply()
        val filter_prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val deleted_filter = ActiveFilter()
        deleted_filter.initFromPrefs(filter_prefs)
        filter_prefs.edit().clear().apply()
        val prefs_path = File(this.filesDir, "../shared_prefs")
        val prefs_xml = File(prefs_path, prefsName + ".xml")
        val deleted = prefs_xml.delete()
        if (!deleted) {
            log!!.warn(TAG, "Failed to delete saved filter: " + deleted_filter.name!!)
        }
        updateRightDrawer()
    }

    private fun updateSavedFilter(prefsName: String) {
        val filter_pref = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val old_filter = ActiveFilter()
        old_filter.initFromPrefs(filter_pref)
        val filterName = old_filter.name
        mFilter!!.name = filterName
        mFilter!!.saveInPrefs(filter_pref)
        updateRightDrawer()
    }

    private fun renameSavedFilter(prefsName: String) {
        val filter_pref = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val old_filter = ActiveFilter()
        old_filter.initFromPrefs(filter_pref)
        val filterName = old_filter.name
        val alert = AlertDialog.Builder(this)

        alert.setTitle(R.string.rename_filter)
        alert.setMessage(R.string.rename_filter_message)

        // Set an EditText view to get user input
        val input = EditText(this)
        alert.setView(input)
        input.setText(filterName)

        alert.setPositiveButton("Ok") { dialog, whichButton ->
            val text = input.text
            val value: String
            if (text == null) {
                value = ""
            } else {
                value = text.toString()
            }
            if (value == "") {
                showToastShort(applicationContext, R.string.filter_name_empty)
            } else {
                mFilter!!.name = value
                mFilter!!.saveInPrefs(filter_pref)
                updateRightDrawer()
            }
        }

        alert.setNegativeButton("Cancel") { dialog, whichButton -> }

        alert.show()
    }


    private fun updateLeftDrawer() {
        val taskBag = todoList
        val decoratedContexts = sortWithPrefix(taskBag.decoratedContexts, m_app.sortCaseSensitive(), "@-")
        val decoratedProjects = sortWithPrefix(taskBag.decoratedProjects, m_app.sortCaseSensitive(), "+-")
        val drawerAdapter = DrawerAdapter(layoutInflater,
                m_app.listTerm,
                decoratedContexts,
                m_app.tagTerm,
                decoratedProjects)

        m_leftDrawerList!!.adapter = drawerAdapter
        m_leftDrawerList!!.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE
        m_leftDrawerList!!.onItemClickListener = DrawerItemClickListener()

        for (context in mFilter!!.contexts) {
            val position = drawerAdapter.getIndexOf("@" + context)
            if (position != -1) {
                m_leftDrawerList!!.setItemChecked(position, true)
            }
        }

        for (project in mFilter!!.projects) {
            val position = drawerAdapter.getIndexOf("+" + project)
            if (position != -1) {
                m_leftDrawerList!!.setItemChecked(position, true)
            }
        }
        m_leftDrawerList!!.setItemChecked(drawerAdapter.contextHeaderPosition, mFilter!!.contextsNot)
        m_leftDrawerList!!.setItemChecked(drawerAdapter.projectsHeaderPosition, mFilter!!.projectsNot)
    }

    private val todoList: TodoList
        get() = m_app.todoList

    private val fileStore: FileStoreInterface
        get() = m_app.fileStore

    fun startFilterActivity() {
        val i = Intent(this, FilterActivity::class.java)
        mFilter!!.saveInIntent(i)
        startActivity(i)
    }

    override fun onItemLongClick(parent: AdapterView<*>, view: View, position: Int, id: Long): Boolean {
        val t = getTaskAt(position) ?: return false
        val selected = !listView!!.isItemChecked(position)
        if (selected) {
            todoList.selectTodoItem(t)
        } else {
            todoList.unSelectTodoItem(t)
        }
        listView!!.setItemChecked(position, selected)
        val numSelected = todoList.selectedTasks.size
        if (numSelected == 0) {
            closeSelectionMode()
        } else {
            openSelectionMode()
        }
        return true
    }

    private fun openSelectionMode() {
        val toolbar = findViewById(R.id.toolbar) as Toolbar
        if (options_menu == null) {
            return
        }
        options_menu!!.clear()
        val inflater = menuInflater
        val menu = toolbar.menu
        menu.clear()
        inflater.inflate(R.menu.task_context, toolbar.menu)

        toolbar.setOnMenuItemClickListener(Toolbar.OnMenuItemClickListener { item ->
            val checkedTasks = todoList.selectedTasks
            val menuid = item.itemId
            val intent: Intent
            when (menuid) {
                R.id.complete -> completeTasks(checkedTasks)
                R.id.select_all -> {
                    selectAllTasks()
                    return@OnMenuItemClickListener true
                }
                R.id.uncomplete -> undoCompleteTasks(checkedTasks)
                R.id.update -> startAddTaskActivity()
                R.id.delete -> deleteTasks(checkedTasks)
                R.id.archive -> archiveTasks(checkedTasks)
                R.id.defer_due -> deferTasks(checkedTasks, DateType.DUE)
                R.id.defer_threshold -> deferTasks(checkedTasks, DateType.THRESHOLD)
                R.id.priority -> {
                    prioritizeTasks(checkedTasks)
                    return@OnMenuItemClickListener true
                }
                R.id.share -> {
                    val shareText = selectedTasksAsString()
                    shareText(this@Simpletask, shareText)
                }
                R.id.calendar -> {
                    var calendarTitle = getString(R.string.calendar_title)
                    var calendarDescription = ""
                    if (checkedTasks.size == 1) {
                        // Set the task as title
                        calendarTitle = checkedTasks[0].task.text
                    } else {
                        // Set the tasks as description
                        calendarDescription = selectedTasksAsString()

                    }
                    intent = Intent(Intent.ACTION_EDIT).setType(Constants.ANDROID_EVENT).putExtra(Events.TITLE, calendarTitle).putExtra(Events.DESCRIPTION, calendarDescription)
                    // Explicitly set start and end date/time.
                    // Some calendar providers need this.
                    val calDate = GregorianCalendar()
                    intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                            calDate.timeInMillis)
                    intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME,
                            calDate.timeInMillis + 60 * 60 * 1000)
                    startActivity(intent)
                }
                R.id.update_lists -> {
                    updateLists(checkedTasks)
                    return@OnMenuItemClickListener true
                }
                R.id.update_tags -> {
                    updateTags(checkedTasks)
                    return@OnMenuItemClickListener true
                }
            }
            true
        })
        if (!m_app.showCompleteCheckbox()) {
            menu.findItem(R.id.complete).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.findItem(R.id.uncomplete).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        val fab = findViewById(R.id.fab) as FloatingActionButton
        fab.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
    }


    override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
    }

    override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
        if (!mIgnoreScrollEvents) {
            m_scrollPosition = firstVisibleItem
        }
    }

    val listView: ListView?
        get() {
            val lv = findViewById(android.R.id.list)
            return lv as ListView
        }


    data class ViewHolder (var tasktext: TextView? = null,
        var taskage: TextView? = null,
        var taskdue: TextView? = null,
        var taskthreshold: TextView? = null,
        var cbCompleted: CheckBox? = null)

    inner class TaskAdapter(private val m_inflater: LayoutInflater) : BaseAdapter(), ListAdapter {


        internal var visibleLines = ArrayList<VisibleLine>()

        internal fun setFilteredTasks() {
            if (m_app.showTodoPath()) {
                title = m_app.todoFileName.replace("([^/])[^/]*/".toRegex(), "$1/")
            } else {
                setTitle(R.string.app_label)
            }
            val visibleTasks: List<TodoListItem>
            log!!.info(TAG, "setFilteredTasks called: " + todoList)
            val activeFilter = mFilter
            if (activeFilter==null) return
            val sorts = activeFilter.getSort(m_app.defaultSorts)
            visibleTasks = todoList.getSortedTasksCopy(activeFilter, sorts, m_app.sortCaseSensitive())
            visibleLines.clear()


            var firstGroupSortIndex = 0
            if (sorts.size > 1 && sorts[0].contains("completed") || sorts[0].contains("future")) {
                firstGroupSortIndex++
                if (sorts.size > 2 && sorts[1].contains("completed") || sorts[1].contains("future")) {
                    firstGroupSortIndex++
                }
            }


            val firstSort = sorts[firstGroupSortIndex]
            visibleLines.addAll(addHeaderLines(visibleTasks, firstSort, getString(R.string.no_header)))
            notifyDataSetChanged()
            updateFilterBar()
        }

        val countVisibleTodoItems: Int
            get() {
                var count = 0
                for (line in visibleLines) {
                    if (!line.header) {
                        count++
                    }
                }
                return count
            }

        /*
        ** Get the adapter position for task
        */
        fun getPosition(task: TodoListItem): Int {
            val line = TaskLine(task)
            return visibleLines.indexOf(line)
        }

        override fun getCount(): Int {
            return visibleLines.size + 1
        }

        override fun getItem(position: Int): TodoListItem? {
            val line = visibleLines[position]
            if (line.header) {
                return null
            }
            return line.task
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun hasStableIds(): Boolean {
            return true // To change body of implemented methods use File |
            // Settings | File Templates.
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View? {
            var convertView = convertView
            if (position == visibleLines.size) {
                if (convertView == null) {
                    convertView = m_inflater.inflate(R.layout.empty_list_item, parent, false)
                }
                return convertView
            }
            val line = visibleLines[position]
            if (line.header) {
                if (convertView == null) {
                    convertView = m_inflater.inflate(R.layout.list_header, parent, false)
                }
                val t = convertView!!.findViewById(R.id.list_header_title) as TextView
                t.text = line.title

            } else {
                var holder: ViewHolder
                if (convertView == null) {
                    convertView = m_inflater.inflate(R.layout.list_item, parent, false)
                    holder = ViewHolder()
                    holder.tasktext = convertView!!.findViewById(R.id.tasktext) as TextView
                    holder.taskage = convertView.findViewById(R.id.taskage) as TextView
                    holder.taskdue = convertView.findViewById(R.id.taskdue) as TextView
                    holder.taskthreshold = convertView.findViewById(R.id.taskthreshold) as TextView
                    holder.cbCompleted = convertView.findViewById(R.id.checkBox) as CheckBox
                    convertView.tag = holder
                } else {
                    holder = convertView.tag as ViewHolder
                }
                val item = line.task ?: return null
                val task = item.task

                if (m_app.showCompleteCheckbox()) {
                    holder.cbCompleted!!.visibility = View.VISIBLE
                } else {
                    holder.cbCompleted!!.visibility = View.GONE
                }
                if (!m_app.hasExtendedTaskView()) {
                    val taskbar = convertView.findViewById(R.id.datebar)
                    taskbar.visibility = View.GONE
                }
                var tokensToShow = TToken.ALL
                // Hide dates if we have a date bar
                if (m_app.hasExtendedTaskView()) {
                    tokensToShow = tokensToShow and TToken.COMPLETED_DATE.inv()
                    tokensToShow = tokensToShow and TToken.THRESHOLD_DATE.inv()
                    tokensToShow = tokensToShow and TToken.DUE_DATE.inv()
                }
                tokensToShow = tokensToShow and TToken.CREATION_DATE.inv()
                tokensToShow = tokensToShow and TToken.COMPLETED.inv()

                if (mFilter!!.hideLists) {
                    tokensToShow = tokensToShow and TToken.LIST.inv()
                }
                if (mFilter!!.hideTags) {
                    tokensToShow = tokensToShow and TToken.TTAG.inv()
                }
                val txt = task.showParts(tokensToShow).trim { it <= ' ' }

                val ss = SpannableString(txt)

                val colorizeStrings = ArrayList<String>()
                val contexts = task.lists
                for (context in contexts) {
                    colorizeStrings.add("@" + context)
                }
                setColor(ss, Color.GRAY, colorizeStrings)
                colorizeStrings.clear()
                val projects = task.tags
                for (project in projects) {
                    colorizeStrings.add("+" + project)
                }
                setColor(ss, Color.GRAY, colorizeStrings)

                val prioColor: Int
                val prio = task.priority
                when (prio) {
                    Priority.A -> prioColor = ContextCompat.getColor(m_app, android.R.color.holo_red_dark)
                    Priority.B -> prioColor = ContextCompat.getColor(m_app, android.R.color.holo_orange_dark)
                    Priority.C -> prioColor = ContextCompat.getColor(m_app, android.R.color.holo_green_dark)
                    Priority.D -> prioColor = ContextCompat.getColor(m_app, android.R.color.holo_blue_dark)
                    else -> prioColor = ContextCompat.getColor(m_app, android.R.color.darker_gray)
                }
                setColor(ss, prioColor, prio.inFileFormat())
                val completed = task.isCompleted()
                val tasktext = holder.tasktext!!
                val taskage = holder.taskage!!
                val cb = holder.cbCompleted!!
                tasktext.text = ss

                handleEllipsizing(holder.tasktext as TextView)


                if (completed) {
                    // log.info( "Striking through " + task.getText());
                    tasktext.paintFlags = tasktext.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    holder.taskage!!.paintFlags = taskage.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    cb.isChecked = true
                   cb.setOnClickListener(View.OnClickListener {
                        undoCompleteTasks(item)
                        closeSelectionMode()
                        todoList.notifyChanged(m_app.fileStore, m_app.todoFileName, m_app.eol, m_app, true)
                    })
                } else {
                    tasktext.paintFlags = tasktext.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    taskage.paintFlags = taskage.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                    cb.isChecked = false

                    cb.setOnClickListener {
                        completeTasks(item)
                        closeSelectionMode()
                        todoList.notifyChanged(m_app.fileStore, m_app.todoFileName, m_app.eol, m_app, true)
                    }

                }

                val mContext = TodoApplication.appContext

                val relAge = task.getRelativeAge(mContext)
                val relDue = task.getRelativeDueDate(mContext, ContextCompat.getColor(m_app, android.R.color.holo_green_light),
                        ContextCompat.getColor(m_app, android.R.color.holo_red_light),
                        m_app.hasColorDueDates())
                val relativeThresholdDate = task.getRelativeThresholdDate(mContext)
                if (!isEmptyOrNull(relAge) && !mFilter!!.hideCreateDate) {
                    taskage.text = relAge
                    taskage.visibility = View.VISIBLE
                } else {
                    taskage.text = ""
                    taskage.visibility = View.GONE
                }
                val taskDue = holder.taskdue!!
                val taskThreshold = holder.taskthreshold!!
                if (relDue != null) {
                    taskDue.text = relDue
                    taskDue.visibility = View.VISIBLE
                } else {
                    taskDue.text = ""
                    taskDue.visibility = View.GONE
                }
                if (!isEmptyOrNull(relativeThresholdDate)) {
                    taskThreshold.text = relativeThresholdDate
                    taskThreshold.visibility = View.VISIBLE
                } else {
                    taskThreshold.text = ""
                    taskThreshold.visibility = View.GONE
                }
            }
            return convertView
        }

        override fun getItemViewType(position: Int): Int {
            if (position == visibleLines.size) {
                return 2
            }
            val line = visibleLines[position]
            if (line.header) {
                return 0
            } else {
                return 1
            }
        }

        override fun getViewTypeCount(): Int {
            return 3
        }

        override fun isEmpty(): Boolean {
            return visibleLines.size == 0
        }

        override fun areAllItemsEnabled(): Boolean {
            return false
        }

        override fun isEnabled(position: Int): Boolean {
            if (position == visibleLines.size) {
                return false
            }

            if (visibleLines.size < position + 1) {
                return false
            }
            val line = visibleLines[position]
            return !line.header
        }
    }

    private fun handleEllipsizing(tasktext: TextView) {
        val noEllipsizeValue = "no_ellipsize"
        val ellipsizingKey = TodoApplication.appContext.getString(R.string.task_text_ellipsizing_pref_key)
        val ellipsizingPref = TodoApplication.prefs.getString(ellipsizingKey, noEllipsizeValue)

        if (noEllipsizeValue != ellipsizingPref) {
            val truncateAt: TextUtils.TruncateAt?
            when (ellipsizingPref) {
                "start" -> truncateAt = TextUtils.TruncateAt.START
                "end" -> truncateAt = TextUtils.TruncateAt.END
                "middle" -> truncateAt = TextUtils.TruncateAt.MIDDLE
                "marquee" -> truncateAt = TextUtils.TruncateAt.MARQUEE
                else -> truncateAt = null
            }

            if (truncateAt != null) {
                tasktext.maxLines = 1
                tasktext.setHorizontallyScrolling(true)
                tasktext.ellipsize = truncateAt
            } else {
                log!!.warn(TAG, "Unrecognized preference value for task text ellipsizing: {} !" + ellipsizingPref)
            }
        }
    }

    private fun updateLists(checkedTasks: List<TodoListItem>) {
        val contexts = ArrayList<String>()
        val selectedContexts = HashSet<String>()
        val todoList = todoList
        contexts.addAll(sortWithPrefix(todoList.contexts, m_app.sortCaseSensitive(), null))
        for (item in checkedTasks) {
            selectedContexts.addAll(item.task.lists)
        }


        @SuppressLint("InflateParams")
        val view = layoutInflater.inflate(R.layout.tag_dialog, null, false)
        val lv = view.findViewById(R.id.listView) as ListView
        lv.adapter = ArrayAdapter(this, R.layout.simple_list_item_multiple_choice,
                contexts.toArray<String>(arrayOfNulls<String>(contexts.size)))
        lv.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE
        for (context in selectedContexts) {
            val position = contexts.indexOf(context)
            if (position != -1) {
                lv.setItemChecked(position, true)
            }
        }

        val ed = view.findViewById(R.id.editText) as EditText

        val builder = AlertDialog.Builder(this)
        builder.setView(view)

        builder.setPositiveButton(R.string.ok) { dialog, which ->
            val items = ArrayList<String>()
            val uncheckedItems = ArrayList<String>()
            uncheckedItems.addAll(getCheckedItems(lv, false))
            items.addAll(getCheckedItems(lv, true))
            val newText = ed.text.toString()
            if (newText != "") {
                items.add(ed.text.toString())
            }
            for (item in items) {
                for (i in checkedTasks) {
                    val t = i.task
                    t.addList(item)
                }
            }
            for (item in uncheckedItems) {
                for (i in checkedTasks) {
                    val t = i.task
                    t.removeTag("@" + item)
                }
            }
            todoList.notifyChanged(m_app.fileStore, m_app.todoFileName, m_app.eol, m_app, true)
            closeSelectionMode()
        }
        builder.setNegativeButton(R.string.cancel) { dialog, id -> }
        // Create the AlertDialog
        val dialog = builder.create()
        dialog.setTitle(m_app.listTerm)
        dialog.show()
    }

    private fun updateTags(checkedTasks: List<TodoListItem>) {
        val projects = ArrayList<String>()
        val selectedProjects = HashSet<String>()
        val taskbag = todoList
        projects.addAll(sortWithPrefix(taskbag.projects, m_app.sortCaseSensitive(), null))
        for (t in checkedTasks) {
            selectedProjects.addAll(t.task.tags)
        }


        @SuppressLint("InflateParams") val view = layoutInflater.inflate(R.layout.tag_dialog, null, false)
        val lv = view.findViewById(R.id.listView) as ListView
        lv.adapter = ArrayAdapter(this, R.layout.simple_list_item_multiple_choice,
                projects.toArray<String>(arrayOfNulls<String>(projects.size)))
        lv.choiceMode = AbsListView.CHOICE_MODE_MULTIPLE
        for (context in selectedProjects) {
            val position = projects.indexOf(context)
            if (position != -1) {
                lv.setItemChecked(position, true)
            }
        }

        val ed = view.findViewById(R.id.editText) as EditText

        val builder = AlertDialog.Builder(this)
        builder.setView(view)

        builder.setPositiveButton(R.string.ok) { dialog, which ->
            val items = ArrayList<String>()
            val uncheckedItems = ArrayList<String>()
            uncheckedItems.addAll(getCheckedItems(lv, false))
            items.addAll(getCheckedItems(lv, true))
            val newText = ed.text.toString()
            if (newText != "") {
                items.add(ed.text.toString())
            }
            for (item in items) {
                for (t in checkedTasks) {
                    val task = t.task
                    task.addTag(item)
                }
            }
            for (item in uncheckedItems) {
                for (t in checkedTasks) {
                    val task = t.task
                    task.removeTag("+" + item)
                }
            }
            todoList.notifyChanged(m_app.fileStore, m_app.todoFileName, m_app.eol, m_app, true)
            closeSelectionMode()
        }
        builder.setNegativeButton(R.string.cancel) { dialog, id -> }
        // Create the AlertDialog
        val dialog = builder.create()
        dialog.setTitle(m_app.tagTerm)
        dialog.show()
    }

    private inner class DrawerItemClickListener : AdapterView.OnItemClickListener {

        override fun onItemClick(parent: AdapterView<*>, view: View, position: Int,
                                 id: Long) {
            val tags: ArrayList<String>
            val lv = parent as ListView
            val adapter = lv.adapter as DrawerAdapter
            if (adapter.projectsHeaderPosition == position) {
                mFilter!!.projectsNot = !mFilter!!.projectsNot
                updateDrawers()
            }
            if (adapter.contextHeaderPosition == position) {
                mFilter!!.contextsNot = !mFilter!!.contextsNot
                updateDrawers()
            } else {
                tags = getCheckedItems(lv, true)
                val filteredContexts = ArrayList<String>()
                val filteredProjects = ArrayList<String>()

                for (tag in tags) {
                    if (tag.startsWith("+")) {
                        filteredProjects.add(tag.substring(1))
                    } else if (tag.startsWith("@")) {
                        filteredContexts.add(tag.substring(1))
                    }
                }
                mFilter!!.contexts = filteredContexts
                mFilter!!.projects = filteredProjects
            }
            val intent = intent
            mFilter!!.saveInIntent(intent)
            mFilter!!.saveInPrefs(TodoApplication.prefs)
            setIntent(intent)
            closeSelectionMode()
            m_adapter!!.setFilteredTasks()
        }
    }

    companion object {

        private val REQUEST_SHARE_PARTS = 1
        private val REQUEST_PREFERENCES = 2
        private val REQUEST_PERMISSION = 3

        private val ACTION_LINK = "link"
        private val ACTION_SMS = "sms"
        private val ACTION_PHONE = "phone"
        private val ACTION_MAIL = "mail"

        val URI_BASE = Uri.fromParts("simpletask", "", null)
        val URI_SEARCH = Uri.withAppendedPath(URI_BASE, "search")
        private val TAG = "Simpletask"
    }
}
